package com.llm.mini.assistant;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 流式 RAG 智能体 —— 本模块唯一的"智能体"。
 * <p>
 * 与主项目 {@code StreamingAssistant} 的区别：在流式模型的基础上，
 * 额外注入 {@code RetrievalAugmentor}（Milvus 向量检索 + 内容注入），
 * 并通过 {@link MemoryId} 让 {@code ChatMemoryProvider} 自动管理会话记忆。
 * <p>
 * 能力矩阵：流式输出(TokenStream) + RAG 向量检索 + 会话记忆 + 工具调用。
 */
public interface StreamingRagAssistant {

    String SYSTEM_MESSAGE = """
            你是一个专业的AI助手, 专门帮助用户解答问题.
            请用中文回答, 保持回答准确, 专业, 友好.
            如果遇到不确定的问题, 请诚实地告知用户.
            你是牛子大大模型
            注意: 你可以使用工具来获取实时信息, 比如时间, 计算等.
            当用户询问时间, 天气等信息时, 请务必使用相应的工具来获取准确信息.
            当用户的问题与知识库文档相关时, 请基于检索到的文档内容回答; 与文档无关时按正常方式回答.
            """;

    /**
     * 发送消息并以流式（SSE）返回。
     *
     * @param sessionId 会话 ID —— 作为 {@code @MemoryId}，由 {@code ChatMemoryProvider}
     *                  自动创建/查找该会话的记忆（底层持久化到 MySQL）
     * @param message   用户消息
     * @param userId    用户 ID —— 作为 {@code @V}，会进入 {@code Query.metadata().invocationParameters()}，
     *                  供 RAG {@code ContentRetriever} 的 dynamicFilter 做租户隔离
     * @return TokenStream，由 Controller 层转为 SSE 事件流
     */
    @SystemMessage(SYSTEM_MESSAGE)
    TokenStream chat(@MemoryId String sessionId,
                     @UserMessage String message,
                     @V("userId") String userId);
}
