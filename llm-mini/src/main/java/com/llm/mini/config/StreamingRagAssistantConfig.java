package com.llm.mini.config;

import com.llm.mini.assistant.StreamingRagAssistant;
import com.llm.mini.pojo.AgentConfig;
import com.llm.mini.service.AgentConfigService;
import com.llm.mini.tool.LlmTool;
import com.llm.mini.util.MemoryKeyUtil;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态流式 RAG 智能体工厂配置。
 * <p>
 * 采用"工厂模式"：Spring 管理一个单例工厂 Bean，运行时按 memoryId
 * （{@code userId:agentId:sessionId}）动态构建 {@link StreamingRagAssistant} 实例。
 * <p>
 * 组装内容：
 * <pre>
 * StreamingChatModel   — DeepSeek 流式模型（逐 token 输出）
 * ChatMemoryProvider   — 会话记忆（MySQL 持久化，按复合 memoryId 隔离）
 * RetrievalAugmentor   — RAG：Milvus 向量检索 + 内容注入（按 userId/agentId 过滤）
 * systemMessageProvider — ★ 动态 SYSTEM_MESSAGE：按复合 memoryId 归属校验后取该 agent 的人设
 * List&lt;LlmTool&gt;        — 工具（CommonTool / TimeTool，@Component 自动收集）
 * </pre>
 */
@Configuration
public class StreamingRagAssistantConfig {

    /** 兜底人设：agent 不存在 / 无权限 / 查询异常时使用（保证隔离——绝不暴露他人 system message） */
    public static final String DEFAULT_SYSTEM_MESSAGE = """
            你是一个专业的AI助手, 专门帮助用户解答问题.
            请用中文回答, 保持回答准确, 专业, 友好.
            如果遇到不确定的问题, 请诚实地告知用户.
            注意: 你可以使用工具来获取实时信息, 比如时间, 计算等.
            当用户询问时间, 天气等信息时, 请务必使用相应的工具来获取准确信息.
            """;

    @Bean
    public StreamingRagAssistantFactory streamingRagAssistantFactory(StreamingChatModel streamingChatModel,
                                                                     List<LlmTool> tools,
                                                                     ChatMemoryProvider chatMemoryProvider,
                                                                     RetrievalAugmentor retrievalAugmentor,
                                                                     AgentConfigService agentConfigService) {
        System.out.println("🔧 [llm-mini] 已注入工具数量: " + tools.size());
        tools.forEach(t -> System.out.println(" - " + t.getClass().getName()));
        return new StreamingRagAssistantFactory(streamingChatModel, tools, chatMemoryProvider,
                retrievalAugmentor, agentConfigService);
    }

    /**
     * 动态流式 RAG Assistant 工厂。
     * <p>
     * 通过 {@code .systemMessageProvider()} 让 SYSTEM_MESSAGE 每次调用都从数据库解析：
     * 智能体修改后下一次聊天立即生效；归属校验保证用户只能加载自己的智能体人设。
     */
    public static class StreamingRagAssistantFactory {
        private final StreamingChatModel streamingChatModel;
        private final List<LlmTool> tools;
        private final ChatMemoryProvider chatMemoryProvider;
        private final RetrievalAugmentor retrievalAugmentor;
        private final AgentConfigService agentConfigService;

        public StreamingRagAssistantFactory(StreamingChatModel streamingChatModel,
                                            List<LlmTool> tools,
                                            ChatMemoryProvider chatMemoryProvider,
                                            RetrievalAugmentor retrievalAugmentor,
                                            AgentConfigService agentConfigService) {
            this.streamingChatModel = streamingChatModel;
            this.tools = tools;
            this.chatMemoryProvider = chatMemoryProvider;
            this.retrievalAugmentor = retrievalAugmentor;
            this.agentConfigService = agentConfigService;
        }

        /**
         * 创建流式 RAG Assistant（记忆 + SYSTEM_MESSAGE 均由框架按复合 memoryId 动态解析）。
         *
         * @return 返回 TokenStream 的动态流式 RAG 智能体代理
         */
        public StreamingRagAssistant createStreamingAssistant() {
            return AiServices.builder(StreamingRagAssistant.class)
                    .streamingChatModel(streamingChatModel)  // ← 流式模型
                    .chatMemoryProvider(chatMemoryProvider)  // ← 会话记忆（MySQL）
                    .retrievalAugmentor(retrievalAugmentor)  // ← RAG 向量检索
                    .systemMessageProvider(this::resolveSystemMessage)  // ← 动态 SYSTEM_MESSAGE
                    .tools(new ArrayList<>(tools))           // ← 工具
                    .build();
        }

        /**
         * 动态解析 SYSTEM_MESSAGE（每次调用执行）：
         * <pre>
         * 复合 memoryId (userId:agentId:sessionId)
         *   → 拆出 userId + agentId
         *   → agentConfigService.getByIdAndOwner(agentId, userId)  ← 归属校验
         *   → 返回该 agent 的 system_message
         * 越权 / 不存在 / 异常 → 回退 DEFAULT_SYSTEM_MESSAGE
         * </pre>
         * 归属校验在查询中完成：即使传了别人的 agentId，也查不到，只能拿到兜底人设。
         */
        private String resolveSystemMessage(Object memoryId) {
            try {
                Long userId = MemoryKeyUtil.userIdOf((String) memoryId);
                Long agentId = MemoryKeyUtil.agentIdOf((String) memoryId);
                AgentConfig agent = agentConfigService.getByIdAndOwner(agentId, userId);
                if (agent != null && agent.getSystemMessage() != null && !agent.getSystemMessage().isBlank()) {
                    return agent.getSystemMessage();
                }
            } catch (Exception e) {
                System.err.println("⚠ 解析智能体 SYSTEM_MESSAGE 失败，使用默认人设: " + e.getMessage());
            }
            return DEFAULT_SYSTEM_MESSAGE;
        }
    }
}
