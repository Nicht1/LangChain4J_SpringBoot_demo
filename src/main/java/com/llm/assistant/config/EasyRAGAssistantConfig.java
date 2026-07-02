package com.llm.assistant.config;

import com.llm.assistant.EasyRAGAssistant;
import com.llm.memory.DatabaseChatMemoryStore;
import com.llm.tool.LlmTool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class EasyRAGAssistantConfig {

    /**
     * 创建 Assistant 工厂 Bean
     */
    @Bean
    public EasyRAGAssistantFactory easyRAGAssistantFactory(ChatModel chatModel,
                                                             DatabaseChatMemoryStore memoryStore,
                                                             List<LlmTool> tools,
                                                           ContentRetriever contentRetriever ) {
        System.out.println("🔧 已注入工具数量: " + tools.size());
        tools.forEach(t -> System.out.println(" - " + t.getClass().getName()));
        return new EasyRAGAssistantConfig.EasyRAGAssistantFactory(chatModel, memoryStore, tools, contentRetriever);
    }

    /**
     * Assistant 工厂类
     */
    public static class EasyRAGAssistantFactory {
        private final ChatModel chatModel;

        private final DatabaseChatMemoryStore memoryStore;

        private final List<LlmTool> tools;

        private final ContentRetriever contentRetriever;

        public EasyRAGAssistantFactory(ChatModel chatModel,
                                       DatabaseChatMemoryStore memoryStore,
                                       List<LlmTool> tools,  ContentRetriever contentRetriever) {
            this.chatModel = chatModel;
            this.memoryStore = memoryStore;
            this.tools = tools;
            this.contentRetriever = contentRetriever;
        }

        /**
         * 为指定会话创建 Assistant
         */
        public EasyRAGAssistant createEasyRAGAssistant(String sessionId) {
            ChatMemory chatMemory = MessageWindowChatMemory.builder()
                    .id(sessionId)
                    .maxMessages(40)
                    .chatMemoryStore(memoryStore)
                    .build();

            return AiServices.builder(EasyRAGAssistant.class)
                    .chatModel(chatModel)
                    .chatMemory(chatMemory)
                    .contentRetriever(contentRetriever)
                    .tools(new ArrayList<>(tools))
                    .build();
        }

        // 已修改为 全局复用 bean 给 springboot 托管
//        private static ContentRetriever createContentRetriever(List<Document> documents) {
//
//            // 为文档及其嵌入创建一个空的向量内存存储
//            InMemoryEmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
//
//            // 调用 工具类 初始化向量化内存存储
//            EmbeddingStoreIngestor.ingest(documents, embeddingStore);
//
//            // 调用检索器 对其初始化
//            return EmbeddingStoreContentRetriever.from(embeddingStore);
//        }
    }

}
