package com.llm.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Component;

import java.util.List;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 自定义 RAG 内容检索器，用来对 用户发送的 UserMessage 进行内容检索，是否有匹配的 RAG 等内容
 */
@Component
public class EasyRAGContentRetriever implements ContentRetriever {

    private final EmbeddingStoreContentRetriever originalRetriever;

    public EasyRAGContentRetriever(EmbeddingStore<TextSegment> embeddingStore) {

        this.originalRetriever = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .maxResults(3)  // 限制检索结果数量
                .minScore(0.6)  // 设置最低相似度阈值
                // 用来分配 文档 给指定用户。例如 用户 A 只能查询 A 文档。用户拥有权限 1 可以查询所有 权限 1 的文档
//                .dynamicFilter(query -> {
//                    String userId = getUserId(query.metadata().chatMemoryId());
//                    return metadataKey("userId").isEqualTo(userId);
//                })
                .build();
    }

    /**
     * 检索
     * @param query
     * @return
     */
    @Override
    public List<Content> retrieve(Query query) {
        return originalRetriever.retrieve(query);
    }
}
