package com.llm.config;

import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EasyRAGRetrievalConfig {

    @Bean
    public RetrievalAugmentor retrievalAugmentor(
            ContentRetriever easyRAGcontentRetriever,
            // 使用用户 Query 加强
//            EasyRAGQueryTransformer queryTransformer ,
            EasyRAGContentInjector easyRAGContentInjector
 ) {

        return DefaultRetrievalAugmentor.builder()
                .contentRetriever(easyRAGcontentRetriever)
                .contentInjector(easyRAGContentInjector)
//                .queryTransformer(queryTransformer)
                .build();
    }
}