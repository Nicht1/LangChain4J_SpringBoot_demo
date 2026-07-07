package com.llm.config;

import com.llm.assistant.StreamingAssistant;
import com.llm.store.DatabaseChatMemoryStore;
import com.llm.tool.LlmTool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式 Assistant 工厂配置。
 * <p>
 * 与 {@link AssistantConfig} 的核心区别：
 * <ul>
 *   <li>使用 {@link StreamingChatModel} 而非普通 ChatModel</li>
 *   <li>Assistant 接口返回 {@code TokenStream} 而非 String</li>
 *   <li>Controller 层通过 SSE（Server-Sent Events）将 token 逐个推送给前端</li>
 * </ul>
 * <p>
 * 流式体验：用户看到的是逐字输出，而非等待完整响应。
 */
@Configuration
public class StreamingAssistantConfig {

    @Bean
    public StreamingAssistantFactory streamingAssistantFactory(StreamingChatModel streamingChatModel,
                                                             List<LlmTool> tools,
                                                               ChatMemoryProvider chatMemoryProvider) {
        System.out.println("🔧 已注入工具数量: " + tools.size());
        tools.forEach(t -> System.out.println(" - " + t.getClass().getName()));
        return new StreamingAssistantFactory(streamingChatModel, tools, chatMemoryProvider);
    }

    /**
     * 流式 Assistant 工厂。
     * <p>
     * 通过 {@code .streamingChatModel()} 绑定流式聊天模型，
     * 返回的 TokenStream 会被 Controller 层消费并转为 SSE 事件流。
     */
    public static class StreamingAssistantFactory {
        private final StreamingChatModel streamingChatModel;
        private final List<LlmTool> tools;
        private final ChatMemoryProvider chatMemoryProvider;

        public StreamingAssistantFactory(StreamingChatModel streamingChatModel,
                                         List<LlmTool> tools, ChatMemoryProvider chatMemoryProvider) {
            this.streamingChatModel = streamingChatModel;
            this.chatMemoryProvider = chatMemoryProvider;
            this.tools = tools;
        }

        /**
         * 为指定会话创建流式 Assistant。
         *
         * @param sessionId 会话标识
         * @return 返回 TokenStream 的流式 Assistant 代理
         */
        //TODO 需要修改 改为 Provider 模式
        public StreamingAssistant createStreamingAssistant(String sessionId) {

            return AiServices.builder(StreamingAssistant.class)
                    .streamingChatModel(streamingChatModel)  // ← 流式模型
                    .chatMemoryProvider(chatMemoryProvider)
                    .tools(new ArrayList<>(tools))
                    .build();
        }
    }
}
