package com.llm.mini.config;

import com.llm.mini.assistant.StreamingRagAssistant;
import com.llm.mini.tool.LlmTool;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 流式 RAG 智能体工厂配置。
 * <p>
 * 采用"工厂模式"：Spring 管理一个单例工厂 Bean，运行时按 sessionId
 * 动态创建绑定特定会话记忆的 {@link StreamingRagAssistant} 实例。
 * <p>
 * 组装内容：
 * <pre>
 * StreamingChatModel   — DeepSeek 流式模型（逐 token 输出）
 * ChatMemoryProvider   — 会话记忆（MySQL 持久化，@MemoryId 驱动）
 * RetrievalAugmentor   — RAG：Milvus 向量检索 + 内容注入
 * List&lt;LlmTool&gt;        — 工具（CommonTool / TimeTool，@Component 自动收集）
 * </pre>
 */
@Configuration
public class StreamingRagAssistantConfig {

    @Bean
    public StreamingRagAssistantFactory streamingRagAssistantFactory(StreamingChatModel streamingChatModel,
                                                                     List<LlmTool> tools,
                                                                     ChatMemoryProvider chatMemoryProvider,
                                                                     RetrievalAugmentor retrievalAugmentor) {
        System.out.println("🔧 [llm-mini] 已注入工具数量: " + tools.size());
        tools.forEach(t -> System.out.println(" - " + t.getClass().getName()));
        return new StreamingRagAssistantFactory(streamingChatModel, tools, chatMemoryProvider, retrievalAugmentor);
    }

    /**
     * 流式 RAG Assistant 工厂。
     * <p>
     * 通过 {@code .streamingChatModel()} 绑定流式聊天模型，{@code .retrievalAugmentor()}
     * 注入 RAG 检索管线，返回的 TokenStream 会被 Service/Controller 层消费并转为 SSE 事件流。
     */
    public static class StreamingRagAssistantFactory {
        private final StreamingChatModel streamingChatModel;
        private final List<LlmTool> tools;
        private final ChatMemoryProvider chatMemoryProvider;
        private final RetrievalAugmentor retrievalAugmentor;

        public StreamingRagAssistantFactory(StreamingChatModel streamingChatModel,
                                            List<LlmTool> tools,
                                            ChatMemoryProvider chatMemoryProvider,
                                            RetrievalAugmentor retrievalAugmentor) {
            this.streamingChatModel = streamingChatModel;
            this.tools = tools;
            this.chatMemoryProvider = chatMemoryProvider;
            this.retrievalAugmentor = retrievalAugmentor;
        }

        /**
         * 创建流式 RAG Assistant（记忆由 {@link ChatMemoryProvider} 自动管理）。
         *
         * @return 返回 TokenStream 的流式 RAG 智能体代理
         */
        public StreamingRagAssistant createStreamingAssistant() {
            return AiServices.builder(StreamingRagAssistant.class)
                    .streamingChatModel(streamingChatModel)  // ← 流式模型
                    .chatMemoryProvider(chatMemoryProvider)  // ← 会话记忆
                    .retrievalAugmentor(retrievalAugmentor)  // ← RAG 向量检索
                    .tools(new ArrayList<>(tools))           // ← 工具
                    .build();
        }
    }
}
