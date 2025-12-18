package com.llm.controller;

import com.llm.service.StreamingChatService;
import com.llm.vo.request.MessageRequestVO;
import com.llm.vo.response.StreamingMessageResponseVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;

/**
 * 流式聊天控制器（SSE 实现版）
 */
@RestController
@RequestMapping("/api/stream/chat")
public class StreamingChatController {

    private final StreamingChatService streamingChatService;

    @Autowired
    public StreamingChatController(StreamingChatService streamingChatService) {
        this.streamingChatService = streamingChatService;
    }

    /**
     * 使用 SSE 进行流式输出
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@RequestBody MessageRequestVO messageRequestVO) {

        // 调用服务开始流式输出

        return streamingChatService.sendMessageStream(messageRequestVO);
    }

    @PostMapping("/stop")
    public Map<String, Object> stopStreaming(@RequestParam String sessionId) {
        boolean stopped = streamingChatService.stopStreaming(sessionId);
        return Map.of(
                "success", stopped,
                "message", stopped ? "流式传输已停止" : "未找到活跃的流式会话",
                "sessionId", sessionId
        );
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "StreamingChatService", "timestamp", System.currentTimeMillis());
    }
}