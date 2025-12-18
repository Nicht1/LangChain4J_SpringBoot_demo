package com.llm.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.injector.ContentInjector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class EasyRAGContentInjector implements ContentInjector {

    private static final PromptTemplate PROMPT_TEMPLATE = PromptTemplate.from("""
            以下是从知识库中检索到的相关文档内容，请基于这些信息来回答用户的问题:
            
            {{contents}}
            
            请注意:
            1. 优先使用上述文档中的信息来回答
            2. 如果文档中没有相关信息，可以使用你的通用知识
            3. 如果不确定答案，请明确告知用户
            """);

    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage chatMessage) {
        // 如果没有检索到内容，直接返回原消息
        if (contents == null || contents.isEmpty()) {
            return chatMessage;
        }

        // 格式化检索到的内容
        String formattedContents = contents.stream()
                .map(content -> content.textSegment().text())
                .collect(Collectors.joining("\n\n---\n\n"));

        // 创建 Prompt
        Map<String, Object> variables = new HashMap<>();
        variables.put("contents", formattedContents);
        Prompt prompt = PROMPT_TEMPLATE.apply(variables);

        // 返回 SystemMessage（而不是 UserMessage）
        return SystemMessage.from(prompt.text());
    }
}
