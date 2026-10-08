package com.llm.mini.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 聊天记忆提供者配置。
 * <p>
 * ChatMemoryProvider 是 LangChain4j 推荐的多会话记忆方案：
 * 每次请求时动态创建 ChatMemory，由 AiServices 框架通过
 * {@code @MemoryId} 自动传入 memoryId，无需手动管理会话。
 * <p>
 * 当前策略：滑动窗口（MessageWindow），每个会话保留最近
 * {@code llm.memory.max-messages}（默认 20）条消息。
 * <p>
 * <b>窗口上限必须与存储层共用同一份配置</b>：两处读不到同一个值，就会出现
 * "内存窗口 20 条、存储层读 50 条"这种不对称——上一版记忆持久化静默失效正是
 * 由此引发。因此窗口大小统一由 {@link ChatMemoryProperties} 提供。
 * <p>
 * <b>存储后端</b>按 {@code llm.memory.store} 由 Spring 注入：
 * {@code redis}（默认，见 {@code RedisChatMemoryStore}）或 {@code mysql}。
 * 这里刻意只依赖 {@link ChatMemoryStore} 接口，不绑定具体实现。
 */
@Configuration
public class ChatMemoryProviderConfig {

    /**
     * 创建 ChatMemoryProvider Bean。
     * <p>
     * 返回值是一个 Lambda：每次调用时根据 memoryId（复合
     * {@code userId:agentId:sessionId}）创建一个新的 MessageWindowChatMemory 实例，
     * 底层存储委托给当前生效的 {@link ChatMemoryStore}。
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(ChatMemoryStore memoryStore,
                                                 ChatMemoryProperties properties) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)                             // 复合 memoryId，隔离 用户×智能体×会话
                .maxMessages(properties.getMaxMessages()) // 滑动窗口大小：最近 N 条
                .chatMemoryStore(memoryStore)             // 当前生效的存储后端
                .build();
    }
}
