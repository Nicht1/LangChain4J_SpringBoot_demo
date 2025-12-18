package com.llm.controller;

import com.llm.service.ChatMemoryProviderService;
import com.llm.service.ChatService;
import com.llm.vo.request.MessageRequestVO;
import com.llm.vo.response.MessageResponseVO;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 聊天控制器
 */
@RestController
@RequestMapping("/api/chatMemoryProvider")
public class ChatMemoryProviderController {

    private final ChatMemoryProviderService chatMemoryProviderService;

    public ChatMemoryProviderController(ChatMemoryProviderService chatMemoryProviderService) {
        this.chatMemoryProviderService = chatMemoryProviderService;
    }

    /**
     * 发送消息
     */
//    @PostMapping("/send")
//    public Map<String, Object> sendMessage(@RequestBody MessageRequestVO messageRequestVO) {
//
//        Map<String, Object> result = new HashMap<>();
//
//        try {
//            MessageResponseVO messageResponseVO = chatMemoryProviderService.sendMessage(messageRequestVO.getSessionId(),
//                    messageRequestVO.getUserId(),
//                    messageRequestVO.getMessage());
//
//            result.put("success", true);
//            result.put("data", messageResponseVO.getResponse());
//            result.put("sessionId", messageResponseVO.getSessionId());
//
//        } catch (Exception e) {
//            result.put("success", false);
//            result.put("message", "发送消息失败: " + e.getMessage());
//        }
//
//        return result;
//    }

    @PostMapping("/sendByUserId")
    public Map<String, Object> sendMessageByUserId(@RequestBody MessageRequestVO messageRequestVO) {

        Map<String, Object> result = new HashMap<>();


        MessageResponseVO messageResponseVO = chatMemoryProviderService.sendMessageByUserChat(messageRequestVO.getSessionId(),
                messageRequestVO.getUserId(),
                messageRequestVO.getMessage());

        result.put("success", true);
        result.put("data", messageResponseVO.getResponse());
        result.put("sessionId", messageResponseVO.getSessionId());



        return result;
    }

    /**
     * 清除记忆
     */
    @PostMapping("/clear")
    public Map<String, Object> clearMemory(@RequestParam String sessionId) {
        Map<String, Object> result = new HashMap<>();

        try {
            chatMemoryProviderService.clearMemory(sessionId);

            result.put("success", true);
            result.put("message", "记忆清除成功");
            result.put("sessionId", sessionId);

        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "清除记忆失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "UP");
        result.put("timestamp", System.currentTimeMillis());
        return result;
    }
}