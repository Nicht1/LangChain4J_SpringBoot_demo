package com.llm.service;

import com.llm.assistant.StreamingAssistant;
import com.llm.config.StreamingAssistantConfig.StreamingAssistantFactory;
import com.llm.store.DatabaseChatMemoryStore;
import com.llm.pojo.ChatSession;
import com.llm.pojo.StreamingSessionState;
import com.llm.tool.LlmTool;
import com.llm.vo.request.MessageRequestVO;
import com.llm.vo.response.StreamingMessageResponseVO;

import dev.langchain4j.service.TokenStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流式聊天服务（SSE 实现）。
 * <p>
 * 核心机制：LangChain4j 的 {@link TokenStream} + Spring 的 {@link SseEmitter} 双流对接。
 * <pre>
 * ChatModel（逐 token 产生）
 *     ↓ TokenStream.onPartialResponse(t → emitter.send(t))
 * SseEmitter（SSE 事件流）
 *     ↓ text/event-stream
 * 浏览器（EventSource API 消费）
 * </pre>
 * <p>
 * 每个 TokenStream 运行在独立线程中，不阻塞 HTTP 响应线程。
 */
@Service
public class StreamingChatService {

    private final StreamingAssistantFactory streamingAssistantFactory;
    private final DatabaseChatMemoryStore memoryStore;
    private final ChatSessionService chatSessionService;

    /** 会话 → Assistant 实例缓存（避免每次请求重建） */
    private final Map<String, StreamingAssistant> streamingAssistants = new ConcurrentHashMap<>();

    /** 活跃的流式会话状态（用于停止/查询） */
    private final Map<String, StreamingSessionState> activeStreamingSessions = new ConcurrentHashMap<>();

    @Autowired
    public StreamingChatService(DatabaseChatMemoryStore memoryStore,
                                 ChatSessionService chatSessionService,
                                 List<LlmTool> tools,
                                 StreamingAssistantFactory streamingAssistantFactory) {
        this.memoryStore = memoryStore;
        this.chatSessionService = chatSessionService;
        this.streamingAssistantFactory = streamingAssistantFactory;
    }

    /**
     * 发送消息并返回 SSE 事件流。
     * <p>
     * 时序：
     * <ol>
     *   <li>确保会话存在（不存在则创建）</li>
     *   <li>创建 SseEmitter（timeout=0 表示不自动超时）</li>
     *   <li>在独立线程中启动 TokenStream</li>
     *   <li>通过回调将 token 逐个推送到 SseEmitter</li>
     *   <li>完成后关闭 emitter</li>
     * </ol>
     * <p>
     * TokenStream 的 4 个回调：
     * <ul>
     *   <li>onPartialResponse — 每个 token 到达时发送 SSE "message" 事件</li>
     *   <li>onToolExecuted — LLM 调用工具时发送 SSE "tool" 事件</li>
     *   <li>onCompleteResponse — 全部生成完毕，发送完整文本并关闭流</li>
     *   <li>onError — 出错时发送错误事件并关闭流</li>
     * </ul>
     *
     * @return SseEmitter 实例（由 Spring MVC 管理并输出到响应流）
     */
    public SseEmitter sendMessageStream(MessageRequestVO messageRequestVO) {
        // 1. 确保会话存在
        ChatSession orCreateSession = chatSessionService.getOrCreateSession(
                messageRequestVO.getSessionId(), messageRequestVO.getUserId());
        String sessionId = orCreateSession.getSessionId();

        // 2. 创建 SSE 发射器（0 = 永不超时，由逻辑控制关闭）
        SseEmitter emitter = new SseEmitter(0L);

        // 3. 独立线程运行 TokenStream（不阻塞 HTTP 线程）
        new Thread(() -> {
            StreamingAssistant assistant = getOrCreateStreamingAssistant(sessionId);

            try {
                TokenStream tokenStream = assistant.chat(messageRequestVO.getMessage());

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

    /** 从缓存获取或创建流式 Assistant */
    private StreamingAssistant getOrCreateStreamingAssistant(String sessionId) {
        return streamingAssistants.computeIfAbsent(sessionId,
                k -> streamingAssistantFactory.createStreamingAssistant());
    }

    /** 停止指定会话的流式传输（从活跃列表中移除） */
    public boolean stopStreaming(String sessionId) {
        StreamingSessionState sessionState = activeStreamingSessions.get(sessionId);
        if (sessionState != null) {
            activeStreamingSessions.remove(sessionId);
            System.out.println("⏹️ 已停止会话的流式传输: " + sessionId);
            return true;
        }
        return false;
    }

    public Map<String, Object> getStreamingSessionInfo(String sessionId) {
        StreamingSessionState state = activeStreamingSessions.get(sessionId);
        if (state != null) {
            return Map.of(
                    "sessionId", state.getSessionId(),
                    "userId", state.getUserId(),
                    "responseLength", state.getResponseBuilder().length(),
                    "isActive", true
            );
        }
        return Map.of("isActive", false);
    }

    /**
     * 清除流式会话：移除缓存 + 停止流 + 删除 DB 记录
     */
    public void clearStreamingMemory(String sessionId) {
        streamingAssistants.remove(sessionId);
        stopStreaming(sessionId);
        memoryStore.deleteMessages(sessionId);
    }
}
