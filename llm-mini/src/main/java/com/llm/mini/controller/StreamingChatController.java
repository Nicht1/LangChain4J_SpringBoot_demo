package com.llm.mini.controller;

import com.llm.mini.service.StreamingChatService;
import com.llm.mini.vo.request.MessageRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.HashMap;
import java.util.Map;

/**
 * 流式聊天控制器 —— 本模块唯一的"流式输出 Chat 出口"。
 * <p>
 * 动态智能体：请求体携带 {@code agentId}（+ userId），
 * 服务端按复合 memoryId 隔离历史 / 解析 SYSTEM_MESSAGE / 过滤 RAG。
 * <p>
 * API 路径：/api/stream/chat
 * <ul>
 *   <li>POST /send   — 发送消息（{agentId, sessionId, userId, message}），返回 SSE 事件流</li>
 *   <li>POST /stop   — 停止当前会话的流式输出</li>
 *   <li>POST /clear  — 清除当前会话记忆（按 用户+智能体+会话）</li>
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
     */
    @PostMapping("/stop")
    public Map<String, Object> stopStreaming(@RequestBody MessageRequestVO messageRequestVO) {
        boolean stopped = streamingChatService.stopStreaming(
                messageRequestVO.getSessionId(), messageRequestVO.getUserId(), messageRequestVO.getAgentId());
        Map<String, Object> result = new HashMap<>();
        result.put("success", stopped);
        result.put("message", stopped ? "流式传输已停止" : "未找到活跃的流式会话");
        result.put("sessionId", messageRequestVO.getSessionId());
        return result;
    }

    /**
     * 清除指定会话的聊天记忆（按 用户+智能体+会话 精确定位）。
     */
    @PostMapping("/clear")
    public Map<String, Object> clearMemory(@RequestBody MessageRequestVO messageRequestVO) {
        streamingChatService.clearMemory(
                messageRequestVO.getSessionId(), messageRequestVO.getUserId(), messageRequestVO.getAgentId());
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "记忆清除成功");
        result.put("sessionId", messageRequestVO.getSessionId());
        return result;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("service", "StreamingChatService");
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }
}
