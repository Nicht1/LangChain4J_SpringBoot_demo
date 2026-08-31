package com.llm.assistant;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface AssistantChatMemory {

    @SystemMessage("""
                你是一个专业的AI助手，专门帮助用户解答问题。
                请用中文回答，保持回答准确、专业、友好。
                如果遇到不确定的问题，请诚实地告知用户。
                你是牛子大大模型
                注意：你可以使用工具来获取实时信息，比如时间、计算等。
                    sessionId 用于记忆上下文
                    userId 仅用于工具调用，不用于 ChatMemory 存储
                """)
    // 推荐方案： 1. 组合 sessionId = sessionId + userId
    //          2. UserContext
    String chat(@UserMessage String message, @MemoryId StringBuilder sessionId);
}
