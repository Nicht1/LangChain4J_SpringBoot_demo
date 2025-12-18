package com.llm.config;

import com.llm.memory.DatabaseChatMemoryStore;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ChatMemoryProviderConfig {

    @Bean
    public ChatMemoryProvider chatMemoryProvider(DatabaseChatMemoryStore memoryStore) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(40)
                .chatMemoryStore(memoryStore)
                .build();
    }
}
