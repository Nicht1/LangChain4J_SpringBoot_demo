package com.llm.mini.service;

import com.llm.mini.assistant.StreamingRagAssistant;
import com.llm.mini.config.StreamingRagAssistantConfig.StreamingRagAssistantFactory;
import com.llm.mini.pojo.ChatSession;
import com.llm.mini.store.DatabaseChatMemoryStore;
import com.llm.mini.util.MemoryKeyUtil;
import com.llm.mini.vo.request.MessageRequestVO;
import com.llm.mini.vo.response.StreamingMessageResponseVO;
import dev.langchain4j.service.TokenStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式聊天服务（SSE 实现）—— 本模块唯一的"流式输出 Chat 出口"。
 * <p>
 * 核心机制：LangChain4j 的 {@link TokenStream} + Spring 的 {@link SseEmitter} 双流对接。
 * <p>
 * 多用户隔离：用复合 memoryId（{@code userId:agentId:sessionId}）贯穿整条链路——
 * 历史存取（ChatMemoryProvider）、SYSTEM_MESSAGE 解析（systemMessageProvider）、RAG 过滤（@V）都基于它。
 */
@Service
public class StreamingChatService {

    private final StreamingRagAssistantFactory streamingRagAssistantFactory;
    private final DatabaseChatMemoryStore memoryStore;
    private final ChatSessionService chatSessionService;

    /** 复合 memoryId → Assistant 实例缓存（仅性能缓存，重启后懒重建，不承载持久化） */
    private final Map<String, StreamingRagAssistant> streamingAssistants = new ConcurrentHashMap<>();

    @Autowired
    public StreamingChatService(DatabaseChatMemoryStore memoryStore,
                                ChatSessionService chatSessionService,
                                StreamingRagAssistantFactory streamingRagAssistantFactory) {
        this.memoryStore = memoryStore;
        this.chatSessionService = chatSessionService;
        this.streamingRagAssistantFactory = streamingRagAssistantFactory;
    }

    /**
     * 发送消息并返回 SSE 事件流。
     * <p>
     * 调用链：
     * <pre>
     * 1. getOrCreateSession(sessionId, userId)          — 会话元数据（按用户隔离）
     * 2. memoryId = userId:agentId:sessionId              — 复合记忆 key
     * 3. assistant = 缓存.get(memoryId) 或 AiServices.build
     * 4. assistant.chat(memoryId, message, @V userId, @V agentId)
     *    → systemMessageProvider 取该 agent 人设（归属校验）
     *    → ChatMemoryProvider 读该会话历史
     *    → RAG dynamicFilter(userId+agentId) 查 Milvus → 注入
     *    → 逐 token SSE 推送
     * 5. onComplete 写回历史
     * </pre>
     *
     * @return SseEmitter 实例（由 Spring MVC 管理并输出到响应流）
     */
    public SseEmitter sendMessageStream(MessageRequestVO messageRequestVO) {
        // 1. 确保会话存在（按 userId 隔离）
        ChatSession orCreateSession = chatSessionService.getOrCreateSession(
                messageRequestVO.getSessionId(), messageRequestVO.getUserId());
        String sessionId = orCreateSession.getSessionId();

        // 2. 复合 memoryId：用户×智能体×会话 三维隔离
        String memoryId = MemoryKeyUtil.build(
                messageRequestVO.getUserId(), messageRequestVO.getAgentId(), sessionId);

        // 3. 创建 SSE 发射器（0 = 永不超时，由逻辑控制关闭）
        SseEmitter emitter = new SseEmitter(0L);

        // 4. 独立线程运行 TokenStream（不阻塞 HTTP 线程）
        new Thread(() -> {
            StreamingRagAssistant assistant = getOrCreateStreamingAssistant(memoryId);

            try {
                TokenStream tokenStream = assistant.chat(
                        memoryId,
                        messageRequestVO.getMessage(),
                        String.valueOf(messageRequestVO.getUserId()),
                        String.valueOf(messageRequestVO.getAgentId()));

                tokenStream
                        // 回调 A：每收到一个 token →
                        .onPartialResponse(token -> {
                            try {
                                StreamingMessageResponseVO response = new StreamingMessageResponseVO()
                                        .setToken(token)
                                        .setSessionId(sessionId)
                                        .setUserId(messageRequestVO.getUserId())
                                        .setComplete(false);
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(response));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        // 回调 B：LLM 调用了工具 →
                        .onToolExecuted(toolExecution -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("tool")
                                        .data("工具执行：" + toolExecution.request().name()
                                                + " 参数：" + toolExecution.request().arguments()
                                                + " 结果：" + toolExecution.result()));
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        // 回调 C：全部 token 生成完毕 →
                        .onCompleteResponse(chatResponse -> {
                            try {
                                String fullText = chatResponse.aiMessage().text();
                                StreamingMessageResponseVO response = new StreamingMessageResponseVO()
                                        .setToken("")
                                        .setSessionId(sessionId)
                                        .setUserId(messageRequestVO.getUserId())
                                        .setComplete(true)
                                        .setFullResponse(fullText);
                                emitter.send(SseEmitter.event()
                                        .name("complete")
                                        .data(response));
                                emitter.complete();  // 正常结束
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        // 回调 D：发生错误 →
                        .onError(error -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data("Error: " + error.getMessage()));
                            } catch (IOException ignored) {}
                            emitter.completeWithError(error);
                        })
                        // 启动流处理
                        .start();

            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("初始化失败: " + e.getMessage()));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    /** 从缓存获取或创建流式 RAG Assistant（按复合 memoryId 缓存） */
    private StreamingRagAssistant getOrCreateStreamingAssistant(String memoryId) {
        return streamingAssistants.computeIfAbsent(memoryId,
                k -> streamingRagAssistantFactory.createStreamingAssistant());
    }

    /** 停止指定会话的流式传输（按复合 memoryId 移除缓存，下次请求重建） */
    public boolean stopStreaming(String sessionId, Long userId, Long agentId) {
        String memoryId = MemoryKeyUtil.build(userId, agentId, sessionId);
        return streamingAssistants.remove(memoryId) != null;
    }

    /** 清除会话记忆：按复合 memoryId 移除缓存 + 删除对应 DB 历史 */
    public void clearMemory(String sessionId, Long userId, Long agentId) {
        String memoryId = MemoryKeyUtil.build(userId, agentId, sessionId);
        streamingAssistants.remove(memoryId);
        memoryStore.deleteMessages(memoryId);
    }
}
