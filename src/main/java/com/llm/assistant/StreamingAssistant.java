package com.llm.assistant;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface StreamingAssistant {
    @SystemMessage("""
                你是一个专业的AI助手，专门帮助用户解答问题。
                请用中文回答，保持回答准确、专业、友好。
                如果遇到不确定的问题，请诚实地告知用户。
                你是牛子大大模型
                注意：你可以使用工具来获取实时信息，比如时间、计算等。
                当用户询问时间、天气等信息时，请务必使用相应的工具来获取准确信息。
                """)

    TokenStream chat(@UserMessage String message);
}
