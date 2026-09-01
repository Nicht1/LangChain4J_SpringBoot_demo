package com.llm.mini.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.logical.And;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * RAG 内容检索器：将用户消息向量化后，在 Milvus 中做相似度检索。
 * <p>
 * 工作流程：
 * <pre>
 * 用户发送消息 → LangChain4j 框架转成 Query
 * EmbeddingModel（BGE-small-zh）将 Query 转成 512 维向量
 * 在 Milvus 中搜索 Top-3 最相似的文档片段
 * 过滤掉相似度 &lt; 0.6 的结果
 * </pre>
 * <p>
 * dynamicFilter —— 多租户 RAG 隔离：
 * 从 {@code Query.metadata().invocationParameters()} 读取智能体方法上的
 * {@code @V("userId")} 与 {@code @V("agentId")}，拼出
 * {@code metadata["userId"]==x AND metadata["agentId"]==y}，
 * 保证每个用户/智能体只检索自己的知识库（向量在摄入时已打 userId/agentId 标签，见 EmbeddingStoreConfig）。
 */
@Component
public class RagContentRetriever implements ContentRetriever {

    private final EmbeddingStoreContentRetriever originalRetriever;

    public RagContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                               EmbeddingModel embeddingModel) {

        this.originalRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)   // Milvus 向量库
                .embeddingModel(embeddingModel)   // BGE-small-zh 做查询向量化
                .maxResults(3)                     // 最多返回 3 条匹配
                .minScore(0.6)                     // 相似度阈值：低于 0.6 的丢弃
                // 动态过滤器：多租户 RAG 隔离
                .dynamicFilter((query) -> {
                    Map<String, Object> params = query.metadata()
                            .invocationParameters()
                            .asMap();
                    Object userId = params.get("userId");
                    Object agentId = params.get("agentId");

                    Filter filter = null;
                    if (userId != null) {
                        filter = metadataKey("userId").isEqualTo(String.valueOf(userId));
                    }
                    if (agentId != null) {
                        Filter agentFilter = metadataKey("agentId").isEqualTo(String.valueOf(agentId));
                        filter = (filter == null) ? agentFilter : new And(filter, agentFilter);
                    }
                    return filter;  // 无任何维度则不过滤
                })
                .build();
    }

    /**
     * 执行检索。
     *
     * @param query 包含用户消息文本和元数据的查询对象
     * @return 与查询最相关的文档片段列表（最多 3 条）
     */
    @Override
    public List<Content> retrieve(Query query) {
        return originalRetriever.retrieve(query);
    }
}
