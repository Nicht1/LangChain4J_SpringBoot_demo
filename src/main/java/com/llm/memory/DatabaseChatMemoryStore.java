package com.llm.memory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llm.mapper.ChatMessageEntityMapper;
import com.llm.mapper.ChatToolCallMapper;
import com.llm.pojo.ChatMessageEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.pojo.ChatToolCall;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class DatabaseChatMemoryStore implements ChatMemoryStore {

    private final ChatMessageEntityMapper chatMessageMapper;

    private final ChatToolCallMapper chatToolCallMapper;

    @Autowired
    public DatabaseChatMemoryStore(ChatMessageEntityMapper chatMessageMapper, ChatToolCallMapper chatToolCallMapper) {

        this.chatMessageMapper = chatMessageMapper;

        this.chatToolCallMapper = chatToolCallMapper;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = (String) memoryId;
        List<ChatMessageEntity> chatMessageEntityList = chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getSessionId, sessionId)
                .orderByAsc(ChatMessageEntity::getCreatedTime)
                .last("LIMIT 50"));

        List<String> messageIdList = chatMessageEntityList.stream().map(ChatMessageEntity::getMessageId).toList();
        Map<String, List<ChatToolCall>> toolRequestsMap = new HashMap<>();
        if (!ObjectUtils.isEmpty(messageIdList)) {
            toolRequestsMap = chatToolCallMapper.selectList(new LambdaQueryWrapper<ChatToolCall>().in(ChatToolCall::getMessageId, messageIdList))
                    .stream().collect(Collectors.groupingBy(ChatToolCall::getMessageId));
        }

        List<ChatMessage> chatMessages = new ArrayList<>();
        for (ChatMessageEntity item : chatMessageEntityList) {
            try {
                ChatMessage chatMessage = this.convertToChatMessage(item, toolRequestsMap.getOrDefault(item.getMessageId(), null));
                chatMessages.add(chatMessage);
            } catch (Exception e) {
                System.err.println("转换消息失败: " + e.getMessage());
            }
        }

        return chatMessages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messageList) {

        String sessionId = (String) memoryId;

        // 查询已有记录数量 (避免每次都全量删除)
        int existingCount = Math.toIntExact(chatMessageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getSessionId, sessionId)
        ));

        // 如果数据库中消息数量 == memory 中数量，则无需更新
        if (existingCount == messageList.size()) {
            return;
        }

        // 只插入新增的部分
        List<ChatMessage> newMessages = messageList.subList(existingCount, messageList.size());

        for (ChatMessage message : newMessages) {
            try {
                ChatMessageEntity entity = convertToChatMessageEntity(sessionId, message);
                chatMessageMapper.insert(entity);

                // 处理工具调用
//                handleToolCalls(sessionId, entity, message);

            } catch (Exception e) {
                System.err.println("保存消息失败: " + e.getMessage());
            }
        }

        System.out.println("💾 增量保存消息到数据库: " + newMessages.size() + " 条");

    }

    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = (String) memoryId;
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessageEntity>().eq(ChatMessageEntity::getSessionId, sessionId));
        chatToolCallMapper.delete(new LambdaQueryWrapper<ChatToolCall>().eq(ChatToolCall::getSessionId, sessionId));
        System.out.println("🗑️ 删除会话消息: " + sessionId);
    }

    private ChatMessage convertToChatMessage(ChatMessageEntity chatMessageEntity, List<ChatToolCall> toolRequests) {
        ChatMessageType chatMessageType = ChatMessageType.valueOf(chatMessageEntity.getMessageType());
        switch (chatMessageType) {
            case AI:
                String content = chatMessageEntity.getContent();
                List<ToolExecutionRequest> reqs = parseToolExecutionRequests(toolRequests);
                if (reqs != null && !reqs.isEmpty()) {
                    return AiMessage.builder()
                            .text(content)
                            .toolExecutionRequests(reqs)
                            .build();
                } else {
                    return AiMessage.from(content);
                }
            case USER:
                return new UserMessage(chatMessageEntity.getContent());
            case SYSTEM:
                return new SystemMessage(chatMessageEntity.getContent());
            case TOOL_EXECUTION_RESULT:
                ChatToolCall toolRequest = toolRequests.get(0);
                return ToolExecutionResultMessage.from(toolRequest.getToolCallId(),
                        toolRequest.getToolName() != null ? toolRequest.getToolName() : "unknown_tool",
                        chatMessageEntity.getContent());
            default:
                throw new IllegalStateException("Unexpected value: " + chatMessageType);
        }
    }

    private ChatMessageEntity convertToChatMessageEntity(String sessionId, ChatMessage chatMessage) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setSessionId(sessionId);
        entity.setMessageType(chatMessage.type().name());

        // 设置消息ID - 使用消息自带的ID或生成新ID
//        if (chatMessage.id() != null) {
//            entity.setMessageId(chatMessage.id());
//        } else {
        entity.setMessageId(generateMessageId(chatMessage.type()));
//        }

        String content = null;


        if (chatMessage instanceof AiMessage aiMessage) {
            content = aiMessage.text();
            if (aiMessage.toolExecutionRequests() != null && !aiMessage.toolExecutionRequests().isEmpty()) {
                List<ChatToolCall> toolRequests = serializeToolExecutionRequests(aiMessage.toolExecutionRequests(), entity.getMessageId());
                if (!aiMessage.toolExecutionRequests().isEmpty()) {
                    System.out.println("toolExecutionRequests: " + aiMessage.toolExecutionRequests());
                    toolRequests.forEach(item -> {
                        item.setMessageId(entity.getMessageId());
                        item.setSessionId(sessionId);
                        chatToolCallMapper.insert(item);
                    });

                }
                System.out.println("🔧 保存AI工具调用: " + aiMessage.toolExecutionRequests().size() + " 个请求");
            }
        } else if (chatMessage instanceof UserMessage userMessage) {
            content = userMessage.singleText();
        } else if (chatMessage instanceof SystemMessage systemMessage) {
            content = systemMessage.text();
        } else if (chatMessage instanceof ToolExecutionResultMessage toolMessage) {
            ChatToolCall chatToolCall = ChatToolCall.builder()
                    .toolCallId(toolMessage.id())
                    .toolName(toolMessage.toolName())
                    .messageId(entity.getMessageId())
                    .sessionId(sessionId)
                    .build();
            chatToolCallMapper.insert(chatToolCall);
            content = toolMessage.text();
        }

        entity.setContent(content);
        entity.setTokens(estimateTokens(content));

        return entity;
    }

    private void handleToolCalls(String sessionId, ChatMessageEntity entity, ChatMessage chatMessage) {

        if (chatMessage instanceof AiMessage aiMessage) {
            if (aiMessage.toolExecutionRequests() != null) {
                for (ToolExecutionRequest req : aiMessage.toolExecutionRequests()) {
                    ChatToolCall toolCall = ChatToolCall.builder()
                            .messageId(entity.getMessageId())
                            .sessionId(sessionId)
                            .toolCallId(req.id())
                            .toolName(req.name())
                            .arguments(req.arguments())
                            .build();
                    chatToolCallMapper.insert(toolCall);
                }
            }
        }

        if (chatMessage instanceof ToolExecutionResultMessage toolResult) {
            ChatToolCall toolCall = ChatToolCall.builder()
                    .messageId(entity.getMessageId())
                    .sessionId(sessionId)
                    .toolCallId(toolResult.id())
                    .toolName(toolResult.toolName())
                    .build();
            chatToolCallMapper.insert(toolCall);
        }
    }

    /**
     * 生成消息ID
     */
    private String generateMessageId(ChatMessageType messageType) {
        String prefix = switch (messageType) {
            case AI -> "ai";
            case USER -> "user";
            case SYSTEM -> "sys";
            case TOOL_EXECUTION_RESULT -> "tool";
            default -> "msg";
        };
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 创建 ToolRequest 映射对象
     */
    private List<ChatToolCall> serializeToolExecutionRequests(List<ToolExecutionRequest> requests, String messageId) {
        if (requests == null || requests.isEmpty()) {
            return null;
        }

        try {
            return requests.stream()
                    .map(req -> ChatToolCall.builder()
                            .toolCallId(req.id())
                            .toolName(req.name())
                            .arguments(req.arguments())
                            .messageId(messageId)
                            .build())
                    .toList();
        } catch (Exception e) {
            System.err.println(e.getMessage());
            return null;
        }
    }


    private List<ToolExecutionRequest> parseToolExecutionRequests(List<ChatToolCall> toolRequests) {
        if (toolRequests == null || ObjectUtils.isEmpty(toolRequests)) {
            return null;
        }

        try {
            List<ToolExecutionRequest> requests = new ArrayList<>();
            toolRequests.forEach(item -> {
                ToolExecutionRequest request = ToolExecutionRequest.builder()
                        .id(item.getToolCallId())
                        .name(item.getToolName())
                        .arguments(item.getArguments())
                        .build();
                requests.add(request);
            });
            return requests;
        } catch (Exception e) {
            System.err.println("❌ 解析工具调用请求失败: " + e.getMessage());
            return null;
        }
    }


    private int estimateTokens(String text) {
        if (text == null) return 0;
        // 简单估算：英文约 1 token = 4 字符，中文约 1 token = 2 字符
        return text.length() / 3;
    }

    /**
     * 清理损坏的会话（可选增强方法）
     */
    public void cleanupCorruptedSessions() {
        // 这里可以添加检测和清理损坏会话的逻辑
        // 例如：查找有工具结果但没有对应AI工具调用的会话
    }

}