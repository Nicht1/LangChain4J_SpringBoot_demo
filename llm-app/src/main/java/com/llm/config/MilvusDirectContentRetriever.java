package com.llm.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * <h2>基于 Milvus SDK 原生的 RAG 检索器（路线 B）</h2>
 * <p>
 * 与 {@link EasyRAGContentRetriever}（走 LangChain4j 封装）的区别：
 *
 * <pre>
 * ┌──────────────────────┬──────────────────────────────────┬─────────────────────────────────┐
 * │                      │ EasyRAGContentRetriever (路线 A)  │ MilvusDirectContentRetriever (B) │
 * ├──────────────────────┼──────────────────────────────────┼─────────────────────────────────┤
 * │ 依赖                  │ LangChain4j EmbeddingStore        │ MilvusClientV2 原生 SDK         │
 * │ userId 过滤表达式       │ metadata["userId"] == "123"     │ userId == "123"                 │
 * │ userId 数据位置         │ metadata JSON 字段内              │ userId 独立标量列（有 STL_SORT 索引）│
 * │ 过滤性能               │ 中等（JSON 字段无专用索引）          │ 好（标量列有独立排序索引）           │
 * │ 向量化                 │ LangChain4j 自动                  │ 手动调 EmbeddingModel            │
 * │ 结果解析               │ LangChain4j 自动                  │ 手动解析 SearchResp → Content     │
 * │ 侵入性                 │ 零（主链路不改）                    │ 彻底绕过 LangChain4j 存储层        │
 * └──────────────────────┴──────────────────────────────────┴─────────────────────────────────┘
 * </pre>
 *
 * <h3>使用方式</h3>
 * 两条路线（A / B）通过配置项 {@code rag.retriever-type} 互斥注册，二选一：
 * <pre>
 * rag:
 *   retriever-type: easy     # 路线 A（默认）：EasyRAGContentRetriever
 *   retriever-type: milvus   # 路线 B：本类（MilvusDirectContentRetriever）
 * </pre>
 * 切换时只需改配置，无需改动代码。
 *
 * <h3>前置条件</h3>
 * 需要 {@link MilvusManualCollectionConfig} 先手动建好 Collection（含 userId 列 + 索引）。
 *
 * @see MilvusManualCollectionConfig 手动建表
 * @see EasyRAGContentRetriever 路线 A（LangChain4j 封装）
 */
@Component
// 路线 B：Milvus 原生 SDK 检索。与路线 A 互斥，通过 rag.retriever-type 切换
@ConditionalOnProperty(name = "rag.retriever-type", havingValue = "milvus")
public class MilvusDirectContentRetriever implements ContentRetriever {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingModel embeddingModel;

    @Value("${langchain4j.milvus.collection-name}")
    private String collectionName;

    @Value("${langchain4j.milvus.dimension}")
    private int dimension;

    /** 返回结果数 */
    private static final int MAX_RESULTS = 3;

    /** 相似度阈值（COSINE 值域 [-1, 1]，越接近 1 越相似） */
    private static final float MIN_SCORE = 0.6f;

    /** Milvus 中向量字段名 */
    private static final String VECTOR_FIELD = "vector";

    /** Milvus 中文本字段名 */
    private static final String TEXT_FIELD = "text";

    public MilvusDirectContentRetriever(MilvusClientV2 milvusClient,
                                        EmbeddingModel embeddingModel) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 执行检索。
     *
     * <h3>流程</h3>
     * <ol>
     *   <li>从 {@code query.metadata().invocationParameters()} 取出 userId</li>
     *   <li>用 EmbeddingModel 将 query.text() 转为 512 维向量</li>
     *   <li>调用 {@code milvusClient.search()} 做 ANNS + 标量过滤</li>
     *   <li>过滤掉相似度 &lt; 0.6 的结果</li>
     *   <li>将 Milvus 返回结果转为 LangChain4j {@link Content} 列表</li>
     * </ol>
     */
    @Override
    public List<Content> retrieve(Query query) {
        // 1. 取出 userId（由 EasyRAGAssistant 的 @V("userId") 传入）
        String userId = (String) query.metadata()
                .invocationParameters()
                .asMap()
                .get("userId");

        // 2. 向量化查询文本
        Embedding queryEmbedding = embeddingModel.embed(query.text()).content();
        List<Float> vectorList = queryEmbedding.vectorAsList();
        float[] queryVector = new float[vectorList.size()];
        for (int i = 0; i < vectorList.size(); i++) {
            queryVector[i] = vectorList.get(i);
        }

        // 3. 构建原生 Milvus 搜索请求
        //    关键：filter 表达式用的是独立标量字段 userId == "xxx"（非 JSON 路径）
        SearchReq searchReq = SearchReq.builder()
                .collectionName(collectionName)
                .data(Collections.singletonList(new FloatVec(queryVector)))
                .annsField(VECTOR_FIELD)                       // 向量搜索字段
                .topK(MAX_RESULTS)
                .outputFields(Collections.singletonList(TEXT_FIELD))  // 返回原始文本
                .filter(buildTenantFilter(userId))              // ★ 原生标量过滤
                .build();

        // 4. 执行搜索
        SearchResp searchResp = milvusClient.search(searchReq);

        // 5. 解析结果
        if (searchResp.getSearchResults() == null
                || searchResp.getSearchResults().isEmpty()) {
            return Collections.emptyList();
        }

        List<Content> contents = new ArrayList<>();
        // SDK v2：getSearchResults() 返回 List<List<SearchResult>>（外层=每个查询，内层=该查询的 TopK 结果）
        for (List<SearchResp.SearchResult> resultList : searchResp.getSearchResults()) {
            for (SearchResp.SearchResult result : resultList) {
                Float score = result.getScore();
                if (score == null || score < MIN_SCORE) continue;  // 低于阈值丢弃

                Object textObj = result.getEntity() != null
                        ? result.getEntity().get(TEXT_FIELD)
                        : null;
                String text = textObj != null ? textObj.toString() : "";

                contents.add(Content.from(text));
            }
        }

        return contents;
    }

    // ======================== 过滤表达式构建 ========================

    /**
     * 构建原生 Milvus 标量过滤表达式。
     * <p>
     * 这是路线 B 的核心优势：不同于路线 A 的 {@code metadata["userId"] == "xxx"}，
     * 这里直接对独立标量列做等值过滤，Milvus 会使用 STL_SORT 索引加速。
     *
     * <h3>支持的表达式模式</h3>
     * <pre>
     * 无 userId   → ""  (不过滤)
     * 单个 tenant  → userId == "123"
     * 将来扩展    → userId in ["123", "456"]
     * </pre>
     */
    private String buildTenantFilter(String userId) {
        if (userId == null || userId.isEmpty()) {
            return "";  // 无租户信息则不过滤
        }
        // 原生 Milvus 表达式：直接对 userId 列做等值判断
        return String.format("userId == \"%s\"", userId);
    }
}
