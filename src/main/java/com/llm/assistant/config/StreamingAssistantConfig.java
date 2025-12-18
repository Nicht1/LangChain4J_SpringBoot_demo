package com.llm.assistant.config;

import com.llm.assistant.StreamingAssistant;
import com.llm.memory.DatabaseChatMemoryStore;
import com.llm.tool.LlmTool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class StreamingAssistantConfig {


    /**
     * 创建 StreamingAssistant 工厂 Bean
     */
    @Bean
    public StreamingAssistantFactory streamingAssistantFactory(StreamingChatModel streamingChatModel,
                                                             DatabaseChatMemoryStore memoryStore,
                                                             List<LlmTool> tools) {
        System.out.println("🔧 已注入工具数量: " + tools.size());
        tools.forEach(t -> System.out.println(" - " + t.getClass().getName()));
        return new StreamingAssistantFactory(streamingChatModel, memoryStore, tools);
    }

    /**
     * Assistant 工厂类
     */
    public static class StreamingAssistantFactory {
        private final StreamingChatModel streamingChatModel;

        private final DatabaseChatMemoryStore memoryStore;

        private final List<LlmTool> tools;

        public StreamingAssistantFactory(StreamingChatModel streamingChatModel,
                                DatabaseChatMemoryStore memoryStore,
                                List<LlmTool> tools) {
            this.streamingChatModel = streamingChatModel;
            this.memoryStore = memoryStore;
            this.tools = tools;
        }

        /**
         * 为指定会话创建 Assistant
         */
        public StreamingAssistant createStreamingAssistant(String sessionId) {
            ChatMemory chatMemory = MessageWindowChatMemory.builder()
                    .id(sessionId)
                    .maxMessages(40)
                    .chatMemoryStore(memoryStore)
                    .build();

            return AiServices.builder(StreamingAssistant.class)
                    .streamingChatModel(streamingChatModel)
                    .chatMemory(chatMemory)
                    .tools(new ArrayList<>(tools))
                    .build();
        }
    }
}
