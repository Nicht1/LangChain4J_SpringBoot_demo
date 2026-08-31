package com.llm.mini.config;

import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 检索增强装配线（向量迁移）。
 * <p>
 * 将两个 RAG 组件串联成一个 {@link RetrievalAugmentor}：
 * <pre>
 * ContentRetriever — 接收用户消息，从 Milvus 向量库检索相关文档片段
 * ContentInjector   — 将检索结果注入到 SystemMessage 中，供 LLM 参考
 * </pre>
 * <p>
 * 该 Bean 被 {@link StreamingRagAssistantConfig} 注入智能体，实现流式 + RAG。
 */
@Configuration
public class RetrievalAugmentorConfig {

    @Bean
    public RetrievalAugmentor retrievalAugmentor(
            ContentRetriever ragContentRetriever,
            RagContentInjector ragContentInjector) {
        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(ragContentRetriever)
                .contentInjector(ragContentInjector)
                .build();
    }
}
