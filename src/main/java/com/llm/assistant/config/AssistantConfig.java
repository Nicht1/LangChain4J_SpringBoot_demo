package com.llm.assistant.config;

import com.llm.assistant.Assistant;
import com.llm.memory.DatabaseChatMemoryStore;
import com.llm.tool.LlmTool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class  AssistantConfig {

    /**
     * 创建 Assistant 工厂 Bean
     */
    @Bean
    public AssistantFactory assistantFactory(ChatModel chatModel,
                                             DatabaseChatMemoryStore memoryStore,
                                             List<LlmTool> tools) {
        System.out.println("🔧 已注入工具数量: " + tools.size());
        tools.forEach(t -> System.out.println(" - " + t.getClass().getName()));
        return new AssistantFactory(chatModel, memoryStore, tools);
    }

    /**
     * Assistant 工厂类
     */
    public static class AssistantFactory {
        private final ChatModel chatModel;

        private final DatabaseChatMemoryStore memoryStore;

        private final List<LlmTool> tools;

        public AssistantFactory(ChatModel chatModel,
                                DatabaseChatMemoryStore memoryStore,
                                List<LlmTool> tools) {
            this.chatModel = chatModel;
            this.memoryStore = memoryStore;
            this.tools = tools;
        }

        /**
         * 为指定会话创建 Assistant
         */
        public Assistant  createAssistant(String sessionId) {
            ChatMemory chatMemory = MessageWindowChatMemory.builder()
                    .id(sessionId)
                    .maxMessages(40)
                    .chatMemoryStore(memoryStore)
                    .build();


            return AiServices.builder(Assistant.class)
                    .chatModel(chatModel)
                    .chatMemory(chatMemory)
                    .tools(new ArrayList<>(tools))
                    .build();
        }

    }
}