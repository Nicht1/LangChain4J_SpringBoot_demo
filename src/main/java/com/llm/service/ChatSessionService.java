package com.llm.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llm.mapper.ChatSessionMapper;
import com.llm.pojo.ChatSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;

import java.util.UUID;

@Service
public class ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;

    @Autowired
    public ChatSessionService(ChatSessionMapper chatSessionMapper) {
        this.chatSessionMapper = chatSessionMapper;
    }

    /**
     * 创建或获取会话
     */
    public ChatSession getOrCreateSession(String sessionId, Long userId) {
        if (!ObjectUtils.isEmpty(sessionId)) {
            ChatSession session = chatSessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
                    .eq(ChatSession::getSessionId, sessionId)
                    .eq(ChatSession::getDeleted, 0));
            if (!ObjectUtils.isEmpty(session)) {
                return session;
            }
        }

        ChatSession session = new ChatSession()
                .setSessionId(ObjectUtils.isEmpty(sessionId) ? UUID.randomUUID().toString() : sessionId)
                .setUserId(userId)
                .setTitle("新对话");

        chatSessionMapper.insert(session);

        return session;
    }

    /**
     * 更新会话标题
     */
    public void updateSessionTitle(String sessionId, String title) {
        chatSessionMapper.update(new LambdaUpdateWrapper<ChatSession>()
                .eq(ChatSession::getSessionId, sessionId)
                .set(ChatSession::getTitle, title));
    }

}
