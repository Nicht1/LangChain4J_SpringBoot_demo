package com.llm.service;

import com.llm.assistant.StreamingAssistant;
import com.llm.assistant.config.StreamingAssistantConfig.StreamingAssistantFactory;
import com.llm.memory.DatabaseChatMemoryStore;
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

@Service
public class StreamingChatService {

    private final StreamingAssistantFactory streamingAssistantFactory;

    private final DatabaseChatMemoryStore memoryStore;

    private final ChatSessionService chatSessionService;


    @Autowired
    public StreamingChatService(DatabaseChatMemoryStore memoryStore, ChatSessionService chatSessionService, List<LlmTool> tools, StreamingAssistantFactory streamingAssistantFactory) {
        this.memoryStore = memoryStore;
        this.chatSessionService = chatSessionService;
        this.streamingAssistantFactory = streamingAssistantFactory;
    }

    // 存储各个会话的流式助手实例
    private final Map<String, StreamingAssistant> streamingAssistants = new ConcurrentHashMap<>();

    // 用于跟踪正在进行的流式会话
    private final Map<String, StreamingSessionState> activeStreamingSessions = new ConcurrentHashMap<>();

    /**
     * 流式发送消息 - 返回 Flux 用于 WebFlux
     *
     * @return
     */
    public SseEmitter sendMessageStream (MessageRequestVO messageRequestVO) {
        ChatSession orCreateSession = chatSessionService.getOrCreateSession(messageRequestVO.getSessionId(), messageRequestVO.getUserId());
        String sessionId = orCreateSession.getSessionId();
        SseEmitter emitter = new SseEmitter(0L); // 不超时

        new Thread(() -> {

            StreamingAssistant assistant = getOrCreateStreamingAssistant(sessionId);

            try {
                TokenStream tokenStream = assistant.chat(messageRequestVO.getMessage());

                tokenStream
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
                        .onCompleteResponse(chatResponse  -> {
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
                                emitter.complete();

                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .onError(error -> {
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data("Error: " + error.getMessage()));
                            } catch (IOException ignored) {}
                            emitter.completeWithError(error);
                        })

                        // 启动流
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

    /**
     * 获取或创建流式助手
     */
    private StreamingAssistant getOrCreateStreamingAssistant(String sessionId) {
        return streamingAssistants.computeIfAbsent(sessionId, streamingAssistantFactory::createStreamingAssistant);
    }

    /**
     * 停止指定会话的流式传输
     */
    public boolean stopStreaming(String sessionId) {
        StreamingSessionState sessionState = activeStreamingSessions.get(sessionId);
        if (sessionState != null) {
            // 在实际实现中，可能需要中断 TokenStream
            // 这里我们只是从活跃会话中移除
            activeStreamingSessions.remove(sessionId);
            System.out.println("⏹️ 已停止会话的流式传输: " + sessionId);
            return true;
        }
        return false;
    }

    /**
     * 获取活跃的流式会话信息
     */
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
     * 判断是否需要生成标题
     */
    private boolean shouldGenerateTitle(String sessionId, String message) {
        return message.length() > 5 && message.length() < 50;
    }

    /**
     * 生成会话标题
     */
    private void generateSessionTitle(String sessionId, String firstMessage) {
        try {
            String title = firstMessage.length() > 20 ?
                    firstMessage.substring(0, 20) + "..." : firstMessage;
            chatSessionService.updateSessionTitle(sessionId, title);
        } catch (Exception e) {
            // 标题生成失败不影响主要功能
        }
    }

    /**
     * 清除流式会话记忆
     */
    public void clearStreamingMemory(String sessionId) {
        // 从内存中移除助手实例
        streamingAssistants.remove(sessionId);

        // 停止活跃的流式传输
        stopStreaming(sessionId);

        // 从数据库中删除消息记录
        memoryStore.deleteMessages(sessionId);
    }
}
