package com.llm.controller;

import com.llm.service.StreamingChatService;
import com.llm.vo.request.MessageRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 流式聊天控制器（SSE 实现）。
 * <p>
 * 与普通聊天接口的区别：返回类型是 {@link SseEmitter}，
 * 通过 Server-Sent Events（SSE）将 LLM 输出的 token 逐个推送给前端，
 * 实现 ChatGPT 式的逐字输出效果。
 * <p>
 * 注意：SSE 是单向流（服务端→客户端），客户端发送消息仍需通过普通 HTTP POST。
 * <p>
 * API 路径：/api/stream/chat
 * <ul>
 *   <li>POST /send  — 发送消息，返回 SSE 事件流</li>
 *   <li>POST /stop  — 停止当前会话的流式输出</li>
 *   <li>GET  /health — 健康检查</li>
 * </ul>
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
     * 发送消息并以 SSE 流式返回。
     * <p>
     * produces = {@link MediaType#TEXT_EVENT_STREAM_VALUE} 告诉浏览器
     * 这是一个 SSE 长连接，前端用 EventSource API 即可消费。
     */
    @PostMapping(value = "/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@RequestBody MessageRequestVO messageRequestVO) {
        return streamingChatService.sendMessageStream(messageRequestVO);
    }

    /**
     * 停止指定会话的流式输出。
     * <p>
     * 前台用户点了"停止生成"按钮时会调用此接口。
     */
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
        return Map.of("status", "UP", "service", "StreamingChatService",
                "timestamp", System.currentTimeMillis());
    }
}
