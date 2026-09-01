package com.llm.mini.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 动态流式 RAG 智能体 —— 本模块唯一的"智能体"。
 * <p>
 * 与静态写死 SYSTEM_MESSAGE 不同，这里<b>没有</b> {@code @SystemMessage} 注解：
 * 系统提示词由工厂的 {@code systemMessageProvider} 按复合 memoryId
 * （{@code userId:agentId:sessionId}）从数据库动态解析，实现"前端创建不同人设的智能体"。
 * <p>
 * 能力矩阵：流式输出(TokenStream) + 动态 SYSTEM_MESSAGE + 会话记忆(MySQL) + RAG 向量检索(Milvus) + 工具。
 */
public interface StreamingRagAssistant {

    /**
     * 发送消息并以流式（SSE）返回。
     *
     * @param memoryId 复合记忆 ID（{userId}:{agentId}:{sessionId}）
     *                 <ul>
     *                   <li>{@code ChatMemoryProvider} 按它存取历史 → 用户×智能体×会话隔离</li>
     *                   <li>{@code systemMessageProvider} 按它归属校验 + 取该 agent 的 SYSTEM_MESSAGE</li>
     *                 </ul>
     * @param message  用户消息
     * @param userId   用户 ID —— {@code @V} 进 RAG Query.invocationParameters，dynamicFilter 做用户级过滤
     * @param agentId  智能体 ID —— {@code @V} 进 RAG Query.invocationParameters，dynamicFilter 做智能体级过滤
     * @return TokenStream，由 Controller 层转为 SSE 事件流
     */
    TokenStream chat(@MemoryId String memoryId,
                     @UserMessage String message,
                     @V("userId") String userId,
                     @V("agentId") String agentId);
}
