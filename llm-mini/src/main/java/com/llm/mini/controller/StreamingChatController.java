package com.llm.mini.controller;

import com.llm.mini.service.StreamingChatService;
import com.llm.mini.vo.request.MessageRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * 流式聊天控制器 —— 本模块唯一的"流式输出 Chat 出口"。
 * <p>
 * 与主项目的区别：主项目有 4 个聊天出口（chat / easyRagChat / chatMemoryProvider / stream），
 * 本模块只保留这一个流式出口，并已集成 RAG 向量检索 + 会话记忆 + 工具。
 * <p>
 * API 路径：/api/stream/chat
 * <ul>
 *   <li>POST /send   — 发送消息，返回 SSE 事件流（唯一的流式 Chat 出口）</li>
 *   <li>POST /stop   — 停止当前会话的流式输出</li>
 *   <li>POST /clear  — 清除当前会话记忆</li>
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

    /**
     * 清除指定会话的聊天记忆。
     * <p>
     * 同时清理内存中的 Assistant 实例和数据库中的历史消息。
     */
    @PostMapping("/clear")
    public Map<String, Object> clearMemory(@RequestParam String sessionId) {
        streamingChatService.clearMemory(sessionId);
        return Map.of("success", true, "message", "记忆清除成功", "sessionId", sessionId);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "StreamingChatService",
                "timestamp", System.currentTimeMillis());
    }
}
