package com.llm.config;

import com.llm.assistant.EasyRAGAssistant;
import com.llm.store.DatabaseChatMemoryStore;
import com.llm.tool.LlmTool;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * EasyRAG Assistant 工厂配置。
 * <p>
 * 与普通 Assistant 的区别：额外注入了 {@link ContentRetriever}，
 * 使得对话时能自动从 Milvus 向量库检索相关文档并注入到上下文中。
 * <p>
 * 架构：
 * <pre>
 * 用户消息 → ContentRetriever(查 Milvus) → ContentInjector(注入 SystemMessage) → ChatModel(生成回答)
 * </pre>
 * ContentRetriever 是全局单例（所有会话共享同一个 Milvus 连接），
 * 而 ChatMemory 是会话级隔离。
 */
@Configuration
public class EasyRAGAssistantConfig {

    @Bean
    public EasyRAGAssistantFactory easyRAGAssistantFactory(ChatModel chatModel,
                                                             List<LlmTool> tools,
                                                           ContentRetriever contentRetriever,
                                                           ChatMemoryProvider chatMemoryProvider) {
        System.out.println("🔧 已注入工具数量: " + tools.size());
        tools.forEach(t -> System.out.println(" - " + t.getClass().getName()));
        return new EasyRAGAssistantConfig.EasyRAGAssistantFactory(chatModel, tools, contentRetriever, chatMemoryProvider);
    }

    /**
     * EasyRAG Assistant 工厂。
     * <p>
     * 在 AiServices.builder 中通过 {@code .contentRetriever()} 注册检索器，
     * LangChain4j 框架会自动在每次调用时触发 RAG 流程：
     * 检索 → 注入 → 生成。
     */
    public static class EasyRAGAssistantFactory {
        private final ChatModel chatModel;
        private final List<LlmTool> tools;
        private final ContentRetriever contentRetriever;

        private final ChatMemoryProvider chatMemoryProvider;


        public EasyRAGAssistantFactory(ChatModel chatModel,
                                       List<LlmTool> tools,
                                       ContentRetriever contentRetriever, ChatMemoryProvider chatMemoryProvider) {
            this.chatModel = chatModel;
            this.tools = tools;
            this.contentRetriever = contentRetriever;
            this.chatMemoryProvider = chatMemoryProvider;
        }

        /**
         * 为指定会话创建带 RAG 能力的 Assistant。
         *
         * @param sessionId 会话标识
         * @return 绑定该会话的 EasyRAGAssistant 实例
         */
        public EasyRAGAssistant createEasyRAGAssistant(String sessionId) {


            return AiServices.builder(EasyRAGAssistant.class)
                    .chatModel(chatModel)
                    .chatMemoryProvider(chatMemoryProvider)
                    .contentRetriever(contentRetriever)  // ← 核心差异：注入 RAG 检索器
                    .tools(new ArrayList<>(tools))
                    .build();
        }
    }
}
