package com.llm.config;

import com.llm.assistant.AssistantChatMemory;
import com.llm.tool.LlmTool;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatMemoryProvider 模式的 Assistant 工厂配置。
 * <p>
 * 与 {@link AssistantConfig} 的核心区别：
 * <ul>
 *   <li>不直接注入 {@code ChatMemory}，而是注入 {@link ChatMemoryProvider}</li>
 *   <li>Assistant 接口通过 {@code @MemoryId} 注解让框架自动管理会话记忆</li>
 *   <li>支持多租户场景：可在 sessionId 中编码 userId 实现用户隔离</li>
 * </ul>
 * <p>
 * 这是 LangChain4j 推荐的会话管理方式，因为：
 * <br>
 * ChatMemoryProvider 是声明式的，框架自动根据 @MemoryId 创建/查找记忆，
 * 比手动传 ChatMemory 更安全、更简洁。
 */
@Configuration
public class AssistantChatMemoryConfig {

    @Bean
    public AssistantChatMemoryFactory assistantChatMemoryFactory(ChatModel chatModel,
                                             List<LlmTool> tools,
                                             ChatMemoryProvider chatMemoryProvider) {
        System.out.println("🔧 已注入工具数量: " + tools.size());
        tools.forEach(t -> System.out.println(" - " + t.getClass().getName()));
        return new AssistantChatMemoryFactory(chatModel, tools, chatMemoryProvider);
    }

    /**
     * ChatMemoryProvider 模式工厂。
     * <p>
     * 关键区别：{@code .chatMemoryProvider(chatMemoryProvider)}
     * 注册的是 Provider 而非 Memory 实例，框架在收到请求时动态调用
     * Provider 来获取对应 @MemoryId 的 ChatMemory。
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
         * 创建使用 ChatMemoryProvider 的 Assistant（无参，记忆由 @MemoryId 驱动）。
         */
        public AssistantChatMemory createAssistant() {
            return AiServices.builder(AssistantChatMemory.class)
                    .chatModel(chatModel)
                    .chatMemoryProvider(chatMemoryProvider)  // ← 声明式记忆管理
                    .tools(new ArrayList<>(tools))
                    .build();
        }
    }
}
