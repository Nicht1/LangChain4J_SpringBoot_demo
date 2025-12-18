package com.llm.assistant;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.ToolMemoryId;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface Assistant {
    @SystemMessage("""
                你是一个专业的AI助手，专门帮助用户解答问题。
                请用中文回答，保持回答准确、专业、友好。
                如果遇到不确定的问题，请诚实地告知用户。
                你是牛子大大模型
                注意：你可以使用工具来获取实时信息，比如时间、计算等。
                """)
    String chat(@UserMessage String message);


    // TODO: 使用 chatMemory 似乎没有办法使用 @MemoryId 带入 Tool，除非使用 chatMemoryProvider 来代替 AiServices Build 的
    //  chatMemory 。
    //  验证后： chatMemoryProvider 也无法进行多 MemoryId 的传入： userId 和 sessionId 的 多租户 和 多用户无法依靠官方内容实
    //  现， 解决方案： 1：使用 UserContext 存储 userId 上下文， 2： MemoryId 使用 userId + : + sessionId 存储，使用时分割
    @SystemMessage("""
             你是一个专业的AI助手，专门帮助用户解答问题。
             请用中文回答，保持回答准确、专业、友好。
             如果遇到不确定的问题，请诚实地告知用户。
             你是牛子大大模型

             重要：
             当前对话的用户ID是 {{userId}}
             当用户需要修改余额、查询账户信息或其他需要用户身份的操作时，
             请直接使用这个用户ID ({{userId}})，不要再询问用户ID是什么。

             可用工具：
             - updateUserMoney: 修改用户余额，需要提供金额(money)和用户ID(userId)

             示例：
             用户说"帮我把余额改成500" -> 调用 updateUserMoney(money=500, userId={{userId}})

             注意：你可以使用工具来获取实时信息，比如时间、计算等。
                """)
    String userChat(@UserMessage String message, @V("userId") Long userId);
}