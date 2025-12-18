package com.llm.assistant;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

public interface EasyRAGAssistant {

    @SystemMessage("""
             你是一个专业的AI助手，专门帮助用户解答问题。
             请用中文回答，保持回答准确、专业、友好。
             如果遇到不确定的问题，请诚实地告知用户。
             你是牛子大大模型，你可以使用 RAG 等查询文档来获取专业内容。
             
            注意：请不要在用户说的话和文档不相关时，自己说出文档相关内容
            当用户的问题与文档内容相关时，请参考文档内容回答。
            如果用户的问题与文档无关，则按正常方式回答。

             """)
    String chat(@UserMessage String message);
}
