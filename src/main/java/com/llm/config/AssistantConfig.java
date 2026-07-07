package com.llm.config;

import com.llm.assistant.Assistant;
import com.llm.store.DatabaseChatMemoryStore;
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

/**
 * 非流式 Assistant 工厂配置。
 * <p>
 * 采用"工厂模式"：Spring 管理一个单例工厂 Bean，运行时按 sessionId
 * 动态创建绑定特定会话记忆的 {@link Assistant} 实例。
 * <p>
 * 为什么不用 {@code @AiService} 直接注入？
 * <br>
 * AiService 是全局单例，无法为每个 session 绑定独立的 ChatMemory。
 * 通过工厂模式，每次 {@code createAssistant(sessionId)} 都会创建
 * 一个新的、带独立记忆窗口的 Assistant，实现会话隔离。
 * <p>
 * 模型分工：对话 → DeepSeek（远程），向量 → BGE-small-zh（本地）
 */
@Configuration
public class AssistantConfig {

    /**
     * 注册 Assistant 工厂 Bean。
     * <p>
     * Spring 自动收集所有实现了 {@link LlmTool} 接口的 {@code @Component} Bean，
     * 注入到 tools 列表中。新增工具只需添加 {@code @Component} 即可自动生效。
     */
    @Bean
    public AssistantFactory assistantFactory(ChatModel chatModel,
                                             DatabaseChatMemoryStore memoryStore,
                                             List<LlmTool> tools,
                                             ChatMemoryProvider chatMemoryProvider) {
        System.out.println("🔧 已注入工具数量: " + tools.size());
        tools.forEach(t -> System.out.println(" - " + t.getClass().getName()));
        return new AssistantFactory(chatModel, memoryStore, tools,  chatMemoryProvider);
    }

    /**
     * Assistant 工厂。
     * <p>
     * 每次调用 {@link #createAssistant(String)} 会：
     * <ol>
     *   <li>创建独立的 MessageWindowChatMemory（最近 40 条）</li>
     *   <li>通过 AiServices.builder 组装 ChatModel + ChatMemory + Tools</li>
     *   <li>返回绑定该会话的 Assistant 代理实例</li>
     * </ol>
     */
    public static class AssistantFactory {
        private final ChatModel chatModel;
        private final DatabaseChatMemoryStore memoryStore;
        private final List<LlmTool> tools;
        private final ChatMemoryProvider chatMemoryProvider;


        public AssistantFactory(ChatModel chatModel,
                                DatabaseChatMemoryStore memoryStore,
                                List<LlmTool> tools, ChatMemoryProvider chatMemoryProvider) {
            this.chatModel = chatModel;
            this.memoryStore = memoryStore;
            this.tools = tools;
            this.chatMemoryProvider = chatMemoryProvider;
        }

        /**
         * 为指定会话创建一个带独立记忆的 Assistant。
         *
         * @param sessionId 会话标识，用作 ChatMemory 的 memoryId
         * @return 绑定该会话的 Assistant 代理实例
         */
        public Assistant createAssistant(String sessionId) {

            // 2. 组装 AiServices：ChatModel + Memory + Tools 三合一
            return AiServices.builder(Assistant.class)
                    .chatModel(chatModel)
                    .chatMemoryProvider(chatMemoryProvider)
                    .tools(new ArrayList<>(tools))
                    .build();
        }
    }
}
