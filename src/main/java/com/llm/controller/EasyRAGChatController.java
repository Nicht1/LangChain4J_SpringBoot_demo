package com.llm.controller;

import com.llm.service.EasyRAGChatService;
import com.llm.vo.request.MessageRequestVO;
import com.llm.vo.response.MessageResponseVO;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * EasyRAG 聊天接口。
 * <p>
 * 与非 RAG 聊天的区别：每个请求会自动在 Milvus 知识库中检索相关文档，
 * 并将检索结果注入到 LLM 上下文，让回答能基于企业内部知识库。
 * <p>
 * API 路径：/api/easyRagChat
 * <ul>
 *   <li>POST /send   — 发送消息（会触发 RAG 检索）</li>
 *   <li>POST /clear  — 清除当前会话记忆</li>
 *   <li>GET  /health — 健康检查</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/easyRagChat")
public class EasyRAGChatController {

    private final EasyRAGChatService easyRAGChatService;

    public EasyRAGChatController(EasyRAGChatService easyRAGChatService) {
        this.easyRAGChatService = easyRAGChatService;
    }

    /**
     * 发送消息（触发 RAG 检索增强）。
     * <p>
     * 请求流程：
     * <ol>
     *   <li>将用户消息向量化（BGE-small-zh）</li>
     *   <li>在 Milvus 向量库中检索 Top-3 相似文档</li>
     *   <li>将检索内容注入 SystemMessage</li>
     *   <li>调用 DeepSeek 生成基于知识库的回答</li>
     * </ol>
     */
    @PostMapping("/send")
    public Map<String, Object> sendMessage(@RequestBody MessageRequestVO messageRequestVO) {
        Map<String, Object> result = new HashMap<>();

        MessageResponseVO messageResponseVO = easyRAGChatService.sendMessage(
                messageRequestVO.getSessionId(),
                messageRequestVO.getUserId(),
                messageRequestVO.getMessage());

        result.put("success", true);
        result.put("data", messageResponseVO.getResponse());
        result.put("sessionId", messageResponseVO.getSessionId());

        return result;
    }

    /**
     * 清除指定会话的聊天记忆。
     * <p>
     * 会同时清理内存中的 Assistant 实例和数据库中的历史消息。
     */
    @PostMapping("/clear")
    public Map<String, Object> clearMemory(@RequestParam String sessionId) {
        Map<String, Object> result = new HashMap<>();
        try {
            easyRAGChatService.clearMemory(sessionId);
            result.put("success", true);
            result.put("message", "记忆清除成功");
            result.put("sessionId", sessionId);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "清除记忆失败: " + e.getMessage());
        }
        return result;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }
}
