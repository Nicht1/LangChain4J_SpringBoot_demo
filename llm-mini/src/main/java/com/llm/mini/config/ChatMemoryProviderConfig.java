package com.llm.mini.config;

import com.llm.mini.store.DatabaseChatMemoryStore;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 聊天记忆提供者配置（记忆迁移）。
 * <p>
 * ChatMemoryProvider 是 LangChain4j 推荐的多会话记忆方案：
 * 每次请求时动态创建 ChatMemory，由 AiServices 框架通过
 * {@code @MemoryId} 自动传入 sessionId，无需手动管理会话。
 * <p>
 * 当前策略：滑动窗口（MessageWindow），每个会话保留最近 40 条消息，
 * 持久化到 MySQL（通过 {@link DatabaseChatMemoryStore}）。
 */
@Configuration
public class ChatMemoryProviderConfig {

    /**
     * 创建 ChatMemoryProvider Bean。
     * <p>
     * 返回值是一个 Lambda：每次调用时根据 memoryId（即 sessionId）
     * 创建一个新的 MessageWindowChatMemory 实例，底层存储委托给
     * DatabaseChatMemoryStore 做数据库持久化。
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(DatabaseChatMemoryStore memoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)               // sessionId，用于隔离不同用户的会话
                .maxMessages(40)            // 滑动窗口大小：最近 40 条消息
                .chatMemoryStore(memoryStore) // MySQL 持久化
                .build();
    }
}
