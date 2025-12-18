package com.llm.assistant.config;

import com.llm.assistant.Assistant;
import com.llm.assistant.AssistantChatMemory;
import com.llm.memory.DatabaseChatMemoryStore;
import com.llm.tool.LlmTool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class  AssistantChatMemoryProviderConfig {

    /**
     * 创建 Assistant 工厂 Bean
     */
    @Bean
    public AssistantChatMemoryFactory assistantChatMemoryFactory(ChatModel chatModel,
                                             List<LlmTool> tools,
                                             ChatMemoryProvider chatMemoryProvider) {
        System.out.println("🔧 已注入工具数量: " + tools.size());
        tools.forEach(t -> System.out.println(" - " + t.getClass().getName()));
        return new AssistantChatMemoryFactory(chatModel, tools, chatMemoryProvider);
    }

    /**
     * Assistant 工厂类
     */
    public static class AssistantChatMemoryFactory {
        private final ChatModel chatModel;

        private final List<LlmTool> tools;

        private final ChatMemoryProvider chatMemoryProvider;


        public AssistantChatMemoryFactory(ChatModel chatModel,
                                List<LlmTool> tools, ChatMemoryProvider chatMemoryProvider) {
            this.chatModel = chatModel;
            this.tools = tools;
            this.chatMemoryProvider = chatMemoryProvider;
        }

        /**
         * 为指定会话创建 Assistant
         */
        public AssistantChatMemory createAssistant(String sessionId) {
            return AiServices.builder(AssistantChatMemory.class)
                    .chatModel(chatModel)
                    .chatMemoryProvider(chatMemoryProvider)
                    .tools(new ArrayList<>(tools))
                    .build();
        }



    }
}