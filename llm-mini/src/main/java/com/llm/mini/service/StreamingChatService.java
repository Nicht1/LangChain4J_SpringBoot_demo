package com.llm.mini.service;

import com.llm.mini.assistant.StreamingRagAssistant;
import com.llm.mini.config.StreamingRagAssistantConfig.StreamingRagAssistantFactory;
import com.llm.mini.pojo.ChatSession;
import com.llm.mini.store.DatabaseChatMemoryStore;
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
 * <pre>
 * StreamingChatModel（逐 token 产生）
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

    private final StreamingRagAssistantFactory streamingRagAssistantFactory;
    private final DatabaseChatMemoryStore memoryStore;
    private final ChatSessionService chatSessionService;

    /** 会话 → Assistant 实例缓存（避免每次请求重建） */
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
            StreamingRagAssistant assistant = getOrCreateStreamingAssistant(sessionId);

            try {
                // 传入 sessionId（@MemoryId）与 userId（@V，供 RAG dynamicFilter 做租户过滤）
                TokenStream tokenStream = assistant.chat(
                        sessionId, messageRequestVO.getMessage(), String.valueOf(messageRequestVO.getUserId()));

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

    /** 从缓存获取或创建流式 RAG Assistant */
    private StreamingRagAssistant getOrCreateStreamingAssistant(String sessionId) {
        return streamingAssistants.computeIfAbsent(sessionId,
                k -> streamingRagAssistantFactory.createStreamingAssistant());
    }

    /** 停止指定会话的流式传输（移除缓存，下次请求重新构建并加载最新记忆） */
    public boolean stopStreaming(String sessionId) {
        return streamingAssistants.remove(sessionId) != null;
    }

    /** 清除会话记忆：移除缓存 + 删除 DB 消息 */
    public void clearMemory(String sessionId) {
        streamingAssistants.remove(sessionId);
        memoryStore.deleteMessages(sessionId);
    }
}
