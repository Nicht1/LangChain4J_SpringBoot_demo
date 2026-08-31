package com.llm.mini.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 内容注入器（向量迁移）：将检索到的文档内容格式化为 SystemMessage，
 * 注入到对话上下文中供 LLM 参考。
 * <p>
 * 注入时机：在 ChatModel 被调用之前，由 LangChain4j RAG 框架自动触发。
 * 注入形式：SystemMessage（而非 UserMessage），优先级更高。
 */
@Component
public class RagContentInjector implements ContentInjector {

    /** 注入模板：{{contents}} 会被替换为检索到的文档文本 */
    private static final PromptTemplate PROMPT_TEMPLATE = PromptTemplate.from("""
            以下是从知识库中检索到的相关文档内容，请基于这些信息来回答用户的问题:

            {{contents}}

            请注意:
            1. 优先使用上述文档中的信息来回答
            2. 如果文档中没有相关信息，可以使用你的通用知识
            3. 如果不确定答案，请明确告知用户
            """);

    /**
     * 执行注入。
     * <p>
     * 如果检索结果为空，直接返回原消息（走正常对话流程，不做 RAG）。
     * 如果有检索结果，用模板包装后返回 SystemMessage。
     */
    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage chatMessage) {
        if (contents == null || contents.isEmpty()) {
            return chatMessage;  // 无检索结果，不做 RAG 增强
        }

        // 拼接多个文档片段，用 --- 分隔
        String formattedContents = contents.stream()
                .map(content -> content.textSegment().text())
                .collect(Collectors.joining("\n\n---\n\n"));

        // 模板变量替换
        Map<String, Object> variables = new HashMap<>();
        variables.put("contents", formattedContents);
        Prompt prompt = PROMPT_TEMPLATE.apply(variables);

        // 以 SystemMessage 形式注入（优先级高于 UserMessage）
        return SystemMessage.from(prompt.text());
    }
}
