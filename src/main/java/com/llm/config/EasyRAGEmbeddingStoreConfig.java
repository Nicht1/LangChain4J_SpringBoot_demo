package com.llm.config;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.segment.TextSegment;

import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class EasyRAGEmbeddingStoreConfig {

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {

        InMemoryEmbeddingStore<TextSegment> store = new InMemoryEmbeddingStore<>();
        // 必须使用真实物理地址，无法使用放在 resource 中被 jar 打包后内容文件
        List<Document> documents = FileSystemDocumentLoader.loadDocuments(
                "D:\\work\\LLM-Spring\\src\\main\\resources\\documentation");

        // 用来给 Document 创建元数据/身份，最后可以根据 MemoryId 用来 分配文档的归属
//        for (Document doc : documents) {
//            doc.metadata().put("userId", "12345");
//            doc.metadata().put("businessType", "robot");
//            doc.metadata().put("source", "filesystem");
//        }

        EmbeddingStoreIngestor.ingest(documents, store);
        return store;
    }
}