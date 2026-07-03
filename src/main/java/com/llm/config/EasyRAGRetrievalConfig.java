package com.llm.config;

import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RAG 检索增强装配线。
 * <p>
 * 将三个 RAG 组件串联成一个 {@link RetrievalAugmentor}：
 * <ol>
 *   <li>{@code ContentRetriever}  — 接收用户消息，从 Milvus 向量库检索相关文档片段</li>
 *   <li>{@code QueryTransformer}  — （可选）对用户查询做增强改写（如拼接 userId）</li>
 *   <li>{@code ContentInjector}   — 将检索结果注入到 SystemMessage 中，供 LLM 参考</li>
 * </ol>
 * <p>
 * 当前 QueryTransformer 处于注释状态，待多租户场景启用。
 *
 * @see EasyRAGContentRetriever
 * @see EasyRAGContentInjector
 */
@Configuration
public class EasyRAGRetrievalConfig {

    /**
     * 组装 RAG 增强器。
     * <p>
     * 使用 LangChain4j 的 DefaultRetrievalAugmentor，按序串联检索 + 注入。
     * 如需启用 QueryTransformer，取消下方注释即可。
     */
    @Bean
    public RetrievalAugmentor retrievalAugmentor(
            ContentRetriever easyRAGcontentRetriever,
            // EasyRAGQueryTransformer queryTransformer,  // TODO: 多租户场景启用
            EasyRAGContentInjector easyRAGContentInjector
    ) {
        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(easyRAGcontentRetriever)
                .contentInjector(easyRAGContentInjector)
                // .queryTransformer(queryTransformer)
                .build();
    }
}
