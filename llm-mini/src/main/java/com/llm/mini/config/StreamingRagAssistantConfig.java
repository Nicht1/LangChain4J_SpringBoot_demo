package com.llm.mini.config;

import com.llm.mini.assistant.StreamingRagAssistant;
import com.llm.mini.pojo.AgentConfig;
import com.llm.mini.service.AgentConfigService;
import com.llm.mini.tool.LlmTool;
import com.llm.mini.util.MemoryKeyUtil;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.rag.content.Content;
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
 * StreamingChatModel     — DeepSeek 流式模型（逐 token 输出）
 * ChatMemoryProvider     — 会话记忆（Redis，按复合 memoryId 隔离）
 * chatRequestTransformer — RAG：Milvus 检索 + 注入（只改本次请求，见下）
 * systemMessageProvider  — ★ 动态 SYSTEM_MESSAGE：按复合 memoryId 归属校验后取该 agent 的人设
 * List&lt;LlmTool&gt;         — 工具（CommonTool / TimeTool，@Component 自动收集）
 * </pre>
 *
 * <h2>⚠️ RAG 为什么用 chatRequestTransformer，而不是 retrievalAugmentor</h2>
 * LangChain4j 的 {@code .retrievalAugmentor(...)} 会把 {@code ContentInjector.inject()} 的
 * <b>返回值直接当作那条用户消息存进 ChatMemory</b>。实测（2026-10-08）后果是：
 * <pre>
 * [USER] 维森·安防系列主要有哪些功能？          ← 原问题
 *        以下是从知识库中检索到的相关文档内容... ← 检索到的文档正文，被固化进历史
 * </pre>
 * 那条消息 JSON 长约 1045 字符，且<b>后续轮次会作为历史被反复重发</b>——
 * 既重复计费，又挤占只有 20 条的会话窗口。
 * <p>
 * {@code chatRequestTransformer} 只在"发出去之前"改这一份 {@link ChatRequest}，
 * <b>不参与记忆写入</b>：记忆里留的始终是原始用户消息。代价是需要自己调检索器
 * （见 {@link RagContentRetriever}）并自行替换消息列表，但换来的是记忆干净 + 行为可控。
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
                                                                     RagContentRetriever ragContentRetriever,
                                                                     RagContentInjector ragContentInjector,
                                                                     AgentConfigService agentConfigService) {
        System.out.println("🔧 [llm-mini] 已注入工具数量: " + tools.size());
        tools.forEach(t -> System.out.println(" - " + t.getClass().getName()));
        return new StreamingRagAssistantFactory(streamingChatModel, tools, chatMemoryProvider,
                ragContentRetriever, ragContentInjector, agentConfigService);
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
        private final RagContentRetriever ragContentRetriever;
        private final RagContentInjector ragContentInjector;
        private final AgentConfigService agentConfigService;

        public StreamingRagAssistantFactory(StreamingChatModel streamingChatModel,
                                            List<LlmTool> tools,
                                            ChatMemoryProvider chatMemoryProvider,
                                            RagContentRetriever ragContentRetriever,
                                            RagContentInjector ragContentInjector,
                                            AgentConfigService agentConfigService) {
            this.streamingChatModel = streamingChatModel;
            this.tools = tools;
            this.chatMemoryProvider = chatMemoryProvider;
            this.ragContentRetriever = ragContentRetriever;
            this.ragContentInjector = ragContentInjector;
            this.agentConfigService = agentConfigService;
        }

        /**
         * 创建流式 RAG Assistant（记忆 + SYSTEM_MESSAGE 均由框架按复合 memoryId 动态解析）。
         *
         * @return 返回 TokenStream 的动态流式 RAG 智能体代理
         */
        public StreamingRagAssistant createStreamingAssistant() {
            return AiServices.builder(StreamingRagAssistant.class)
                    .streamingChatModel(streamingChatModel)          // ← 流式模型
                    .chatMemoryProvider(chatMemoryProvider)          // ← 会话记忆（Redis）
                    .chatRequestTransformer(this::augmentWithRag)    // ← RAG：只改本次请求
                    .systemMessageProvider(this::resolveSystemMessage) // ← 动态 SYSTEM_MESSAGE
                    .tools(new ArrayList<>(tools))                   // ← 工具
                    .build();
        }

        /**
         * RAG 增强：在请求发出前，把知识库命中的片段并入最后一条用户消息。
         * <p>
         * 只影响本次 {@link ChatRequest}，<b>不会写进会话记忆</b>。
         * 检索为空则原样放行（走普通对话）。任何异常也只降级为"本轮无 RAG"，
         * 不能让知识库的问题拖垮对话本身。
         *
         * @param request  待发出的请求（记忆已组装好）
         * @param memoryId 复合 memoryId（{@code userId:agentId:sessionId}），身份隔离标签来源
         */
        private ChatRequest augmentWithRag(ChatRequest request, Object memoryId) {
            try {
                List<ChatMessage> messages = request.messages();
                int lastUserIndex = lastUserMessageIndex(messages);
                if (lastUserIndex < 0 || !(messages.get(lastUserIndex) instanceof UserMessage userMessage)) {
                    return request;
                }

                String memoryKey = String.valueOf(memoryId);
                List<Content> contents = ragContentRetriever.retrieve(
                        userMessage.singleText(),
                        MemoryKeyUtil.userIdOf(memoryKey),
                        MemoryKeyUtil.agentIdOf(memoryKey));
                if (contents.isEmpty()) {
                    return request;   // 未命中知识库，不做增强
                }

                ChatMessage augmented = ragContentInjector.inject(contents, userMessage);
                List<ChatMessage> updated = new ArrayList<>(messages);
                updated.set(lastUserIndex, augmented);
                return request.toBuilder().messages(updated).build();
            } catch (Exception e) {
                System.err.println("⚠ RAG 增强失败，本轮按无检索放行: " + e.getMessage());
                return request;
            }
        }

        /** 找最后一条 UserMessage 的下标（工具调用回合里最后一条用户消息未必在末尾附近，必须从后往前找） */
        private static int lastUserMessageIndex(List<ChatMessage> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof UserMessage) {
                    return i;
                }
            }
            return -1;
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
