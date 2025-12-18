package com.llm.service;

import com.llm.assistant.Assistant;
import com.llm.assistant.AssistantChatMemory;
import com.llm.assistant.config.AssistantChatMemoryProviderConfig.AssistantChatMemoryFactory;
import com.llm.assistant.config.AssistantConfig.AssistantFactory;
import com.llm.memory.DatabaseChatMemoryStore;
import com.llm.pojo.ChatSession;
import com.llm.vo.response.MessageResponseVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;


@Service
public class ChatMemoryProviderService {

    private final AssistantChatMemoryFactory assistantChatMemoryFactory;

    private final DatabaseChatMemoryStore memoryStore;

    private final ChatSessionService chatSessionService;

    private final Map<String, AssistantChatMemory> sessionAssistants = new HashMap<>();



    @Autowired
    public ChatMemoryProviderService(AssistantChatMemoryFactory assistantChatMemoryFactory, DatabaseChatMemoryStore memoryStore, ChatSessionService chatSessionService) {
        this.assistantChatMemoryFactory = assistantChatMemoryFactory;

        this.memoryStore = memoryStore;
        this.chatSessionService = chatSessionService;

    }

//    /**
//     * 发送消息
//     */
//    public MessageResponseVO sendMessage(String sessionId, Long userId, String message) {
//        // 确保会话存在
//        ChatSession orCreateSession = chatSessionService.getOrCreateSession(sessionId, userId);
//        sessionId = orCreateSession.getSessionId();
//
//        // 获取或创建会话特定的助手
//        AssistantChatMemory assistant = this.getOrCreateAssistant(sessionId);
//
//
//        // 生成回复
//        String response = assistant.chat(message, userId);
//
//
//
//        // 如果这是会话的第一条消息，尝试生成标题
//        if (shouldGenerateTitle(sessionId, message)) {
//            generateSessionTitle(sessionId, message);
//        }
//
//        return new MessageResponseVO().setResponse(response).setSessionId(sessionId).setUserId(userId);
//    }

    /**
     * 发送消息,根据 userId，来进行数据库操作
     */
    public MessageResponseVO sendMessageByUserChat(String sessionId, Long userId, String message) {

        // 确保会话存在
        ChatSession orCreateSession = chatSessionService.getOrCreateSession(sessionId, userId);
        sessionId = orCreateSession.getSessionId();

        // 获取或创建会话特定的助手
        AssistantChatMemory assistant = this.getOrCreateAssistant(sessionId);

        StringBuilder sessionUserId = new StringBuilder();
        sessionUserId.append(sessionId).append(userId);
        String response = assistant.chat(message, sessionUserId);

        // 如果这是会话的第一条消息，尝试生成标题
        if (shouldGenerateTitle(sessionId, message)) {
            generateSessionTitle(sessionId, message);
        }

        return new MessageResponseVO().setResponse(response).setSessionId(sessionId).setUserId(userId);
    }



    /**
     * 获取或创建助手
     */
    private AssistantChatMemory getOrCreateAssistant(String sessionId) {
        return sessionAssistants.computeIfAbsent(sessionId, assistantChatMemoryFactory::createAssistant);

    }

    /**
     * 判断是否需要生成标题
     */
    private boolean shouldGenerateTitle(String sessionId, String message) {
        // 简化实现：如果消息长度适合作为标题，且会话还没有自定义标题
        // 实际中可以查询数据库判断是否已经设置过标题
        return message.length() > 5 && message.length() < 50;
    }

    /**
     * 生成会话标题
     */
    private void generateSessionTitle(String sessionId, String firstMessage) {
        try {
            // 使用第一条消息作为标题，或截取前20个字符
            String title = firstMessage.length() > 20 ?
                    firstMessage.substring(0, 20) + "..." : firstMessage;
            chatSessionService.updateSessionTitle(sessionId, title);
        } catch (Exception e) {
            // 标题生成失败不影响主要功能
        }
    }

    /**
     * 清除会话记忆
     */
    public void clearMemory(String sessionId) {
        // 从内存中移除助手实例
        sessionAssistants.remove(sessionId);

        // 从数据库中删除消息记录
        memoryStore.deleteMessages(sessionId);
    }
}





