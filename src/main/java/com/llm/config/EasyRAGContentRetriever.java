package com.llm.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 内容检索器：将用户消息向量化后，在 Milvus 中做相似度检索。
 * 工作流程：
 *   用户发送消息 → LangChain4j 框架转成 Query
 *   EmbeddingModel（BGE-small-zh）将 Query 转成 512 维向量
 *   在 Milvus 中搜索 Top-3 最相似的文档片段
 *   过滤掉相似度 &lt; 0.6 的结果<
 * 可扩展点（已注释）：
 * {@code dynamicFilter} — 按 userId 过滤文档，实现权限隔离。
 * 启用后用户 A 只能搜到自己有权限的文档。
 */
@Component
public class EasyRAGContentRetriever implements ContentRetriever {

    private final EmbeddingStoreContentRetriever originalRetriever;

    public EasyRAGContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                                   EmbeddingModel embeddingModel) {

        this.originalRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)   // Milvus 向量库
                .embeddingModel(embeddingModel)   // BGE-small-zh 做查询向量化
                .maxResults(3)                     // 最多返回 3 条匹配
                .minScore(0.6)                     // 相似度阈值：低于 0.6 的丢弃
                // .dynamicFilter(query -> {        // TODO: 多租户权限过滤
                //     String userId = getUserId(query.metadata().chatMemoryId());
                //     return metadataKey("userId").isEqualTo(userId);
                // })
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
