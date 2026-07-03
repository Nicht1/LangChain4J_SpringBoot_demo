package com.llm.store;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llm.mapper.ChatMessageEntityMapper;
import com.llm.mapper.ChatToolCallMapper;
import com.llm.pojo.ChatMessageEntity;
import com.llm.pojo.ChatToolCall;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.util.ObjectUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 基于 MySQL 的 ChatMemory 持久化存储。
 * <p>
 * 实现 LangChain4j 的 {@link ChatMemoryStore} 接口，将每轮对话的
 * AiMessage / UserMessage / SystemMessage / ToolExecutionResultMessage
 * 以及工具调用记录持久化到数据库中，实现跨应用重启的会话记忆。
 * <p>
 * 核心设计：
 * <ul>
 *   <li><b>消息存储</b> — chat_message_entity 表，按 sessionId + createdTime 排序</li>
 *   <li><b>工具调用</b> — chat_tool_call 表，通过 messageId 关联到父消息</li>
 *   <li><b>增量写入</b> — updateMessages 只插入新增消息，不删除全量重建</li>
 *   <li><b>消息转换</b> — DB 实体 ⟷ LangChain4j ChatMessage 的双向映射</li>
 * </ul>
 * <p>
 * 表关系：
 * <pre>
 * chat_message_entity (1) ──< (N) chat_tool_call
 *    通过 messageId 关联
 * </pre>
 */
@Repository
public class DatabaseChatMemoryStore implements ChatMemoryStore {

    private final ChatMessageEntityMapper chatMessageMapper;
    private final ChatToolCallMapper chatToolCallMapper;

    @Autowired
    public DatabaseChatMemoryStore(ChatMessageEntityMapper chatMessageMapper,
                                   ChatToolCallMapper chatToolCallMapper) {
        this.chatMessageMapper = chatMessageMapper;
        this.chatToolCallMapper = chatToolCallMapper;
    }

    // ==================== ChatMemoryStore 接口实现 ====================

    /**
     * 读取会话的所有历史消息。
     * <p>
     * 加载步骤：
     * <ol>
     *   <li>查询该 session 的消息（按时间排序，最多 50 条）</li>
     *   <li>批量查询关联的 tool_call 记录，按 messageId 分组</li>
     *   <li>逐条将 DB 实体转换回 LangChain4j ChatMessage</li>
     * </ol>
     *
     * @param memoryId 实际为 sessionId（String）
     * @return 该会话的所有历史消息（含工具调用）
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sessionId = (String) memoryId;

        // 1. 查询消息（按时间升序，窗口限制 50 条）
        List<ChatMessageEntity> chatMessageEntityList = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getSessionId, sessionId)
                        .orderByAsc(ChatMessageEntity::getCreatedTime)
                        .last("LIMIT 50")
        );

        // 2. 批量查询关联的工具调用，按 messageId 分组
        List<String> messageIdList = chatMessageEntityList.stream()
                .map(ChatMessageEntity::getMessageId).toList();
        Map<String, List<ChatToolCall>> toolRequestsMap = new HashMap<>();
        if (!ObjectUtils.isEmpty(messageIdList)) {
            toolRequestsMap = chatToolCallMapper.selectList(
                    new LambdaQueryWrapper<ChatToolCall>()
                            .in(ChatToolCall::getMessageId, messageIdList)
            ).stream().collect(Collectors.groupingBy(ChatToolCall::getMessageId));
        }

        // 3. 逐条转换：DB 实体 → LangChain4j ChatMessage
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (ChatMessageEntity item : chatMessageEntityList) {
            try {
                ChatMessage chatMessage = this.convertToChatMessage(
                        item, toolRequestsMap.getOrDefault(item.getMessageId(), null));
                chatMessages.add(chatMessage);
            } catch (Exception e) {
                System.err.println("转换消息失败: " + e.getMessage());
            }
        }

        return chatMessages;
    }

    /**
     * 增量保存新消息到数据库。
     * <p>
     * 优化策略：不删除全量重建，而是比对记录数，只插入新增的消息。
     * <pre>
     * if (DB中记录数 == 内存中消息数) → 跳过（无新消息）
     * else → 只插入 [记录数, 末尾] 的新消息
     * </pre>
     * <p>
     * 前提假设：消息是追加式的，不会修改或删除已有消息。
     * 如果 memory.clear() 被调用，会走 deleteMessages 全量删除，
     * 然后下一轮 updateMessages 从 0 开始全部重写。
     *
     * @param memoryId    实际为 sessionId
     * @param messageList 当前会话的完整消息列表
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messageList) {
        String sessionId = (String) memoryId;

        // 比对数量，避免不必要的写入
        int existingCount = Math.toIntExact(chatMessageMapper.selectCount(
                new LambdaQueryWrapper<ChatMessageEntity>()
                        .eq(ChatMessageEntity::getSessionId, sessionId)
        ));

        if (existingCount == messageList.size()) {
            return;  // 无新消息，跳过
        }

        // 只插入新增的尾部消息
        List<ChatMessage> newMessages = messageList.subList(existingCount, messageList.size());

        for (ChatMessage message : newMessages) {
            try {
                ChatMessageEntity entity = convertToChatMessageEntity(sessionId, message);
                chatMessageMapper.insert(entity);
            } catch (Exception e) {
                System.err.println("保存消息失败: " + e.getMessage());
            }
        }

        System.out.println("💾 增量保存消息到数据库: " + newMessages.size() + " 条");
    }

    /**
     * 删除指定会话的全部消息和工具调用记录。
     * <p>
     * 两个表都要清理：chat_message_entity + chat_tool_call。
     *
     * @param memoryId 实际为 sessionId
     */
    @Override
    public void deleteMessages(Object memoryId) {
        String sessionId = (String) memoryId;
        chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessageEntity>()
                .eq(ChatMessageEntity::getSessionId, sessionId));
        chatToolCallMapper.delete(new LambdaQueryWrapper<ChatToolCall>()
                .eq(ChatToolCall::getSessionId, sessionId));
        System.out.println("🗑️ 删除会话消息: " + sessionId);
    }

    // ==================== 消息转换：DB 实体 → LangChain4j 对象 ====================

    /**
     * 将数据库实体转换为 LangChain4j ChatMessage。
     * <p>
     * 根据 messageType 分四种情况处理：
     * <ul>
     *   <li>AI         → AiMessage（可能附带 ToolExecutionRequest）</li>
     *   <li>USER       → UserMessage</li>
     *   <li>SYSTEM     → SystemMessage</li>
     *   <li>TOOL_EXECUTION_RESULT → ToolExecutionResultMessage</li>
     * </ul>
     *
     * @param chatMessageEntity 数据库消息实体
     * @param toolRequests      该消息关联的工具调用记录（可能为空）
     * @return LangChain4j ChatMessage 对象
     */
    private ChatMessage convertToChatMessage(ChatMessageEntity chatMessageEntity,
                                              List<ChatToolCall> toolRequests) {
        ChatMessageType chatMessageType = ChatMessageType.valueOf(chatMessageEntity.getMessageType());
        switch (chatMessageType) {
            case AI:
                String content = chatMessageEntity.getContent();
                List<ToolExecutionRequest> reqs = parseToolExecutionRequests(toolRequests);
                if (reqs != null && !reqs.isEmpty()) {
                    // AI 消息携带了工具调用请求
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
                // 工具执行结果需要 toolCallId 和 toolName
                ChatToolCall toolRequest = toolRequests.get(0);
                return ToolExecutionResultMessage.from(
                        toolRequest.getToolCallId(),
                        toolRequest.getToolName() != null ? toolRequest.getToolName() : "unknown_tool",
                        chatMessageEntity.getContent());
            default:
                throw new IllegalStateException("Unexpected value: " + chatMessageType);
        }
    }

    // ==================== 消息转换：LangChain4j 对象 → DB 实体 ====================

    /**
     * 将 LangChain4j ChatMessage 转换为数据库实体并持久化。
     * <p>
     * 特殊处理：
     * <ul>
     *   <li>AiMessage 如果包含 ToolExecutionRequest，需额外写入 chat_tool_call 表</li>
     *   <li>ToolExecutionResultMessage 需要记录 toolCallId 以关联到对应的请求</li>
     * </ul>
     *
     * @param sessionId   会话标识
     * @param chatMessage LangChain4j 消息对象
     * @return 持久化后的数据库实体
     */
    private ChatMessageEntity convertToChatMessageEntity(String sessionId, ChatMessage chatMessage) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setSessionId(sessionId);
        entity.setMessageType(chatMessage.type().name());
        entity.setMessageId(generateMessageId(chatMessage.type()));

        String content = null;

        if (chatMessage instanceof AiMessage aiMessage) {
            content = aiMessage.text();
            // AI 调用了工具 → 将工具调用请求写入 chat_tool_call 表
            if (aiMessage.toolExecutionRequests() != null
                    && !aiMessage.toolExecutionRequests().isEmpty()) {
                List<ChatToolCall> toolRequests = serializeToolExecutionRequests(
                        aiMessage.toolExecutionRequests(), entity.getMessageId());
                toolRequests.forEach(item -> {
                    item.setMessageId(entity.getMessageId());
                    item.setSessionId(sessionId);
                    chatToolCallMapper.insert(item);
                });
                System.out.println("🔧 保存AI工具调用: " + aiMessage.toolExecutionRequests().size() + " 个请求");
            }
        } else if (chatMessage instanceof UserMessage userMessage) {
            content = userMessage.singleText();
        } else if (chatMessage instanceof SystemMessage systemMessage) {
            content = systemMessage.text();
        } else if (chatMessage instanceof ToolExecutionResultMessage toolMessage) {
            // 工具执行结果：记录 toolCallId 用于关联 AI 的工具调用请求
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

    // ==================== 工具方法 ====================

    /**
     * 序列化工具调用请求：LangChain4j 对象 → DB 实体列表。
     */
    private List<ChatToolCall> serializeToolExecutionRequests(List<ToolExecutionRequest> requests,
                                                               String messageId) {
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

    /**
     * 反序列化工具调用：DB 实体列表 → LangChain4j ToolExecutionRequest 列表。
     */
    private List<ToolExecutionRequest> parseToolExecutionRequests(List<ChatToolCall> toolRequests) {
        if (toolRequests == null || ObjectUtils.isEmpty(toolRequests)) {
            return null;
        }
        try {
            List<ToolExecutionRequest> requests = new ArrayList<>();
            toolRequests.forEach(item -> {
                requests.add(ToolExecutionRequest.builder()
                        .id(item.getToolCallId())
                        .name(item.getToolName())
                        .arguments(item.getArguments())
                        .build());
            });
            return requests;
        } catch (Exception e) {
            System.err.println("❌ 解析工具调用请求失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 生成全局唯一的消息 ID。
     * 格式：{type}_{uuid8}，例如 ai_a3f2c81b
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
     * 粗略估算 token 数量。
     * 混合中英文的简单算法：平均每 3 字符 ≈ 1 token。
     */
    private int estimateTokens(String text) {
        if (text == null) return 0;
        return text.length() / 3;
    }
}
