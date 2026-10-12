package com.llm.mini.config;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
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
 * RAG 内容注入器：把检索到的文档片段并入用户消息，供 LLM 参考。
 *
 * 契约要点（改之前先读）：ContentInjector 的语义是"把 contents 注入到给定的 UserMessage 里"，
 * 返回值会替换掉请求里那条用户消息。所以：
 * - 模板必须带 {{userMessage}}，否则用户的问题会被整条丢掉，模型只能对着文档空谈；
 * - 必须返回 UserMessage。曾经这里返回 SystemMessage.from(...) 且模板只有 {{contents}}，
 *   结果检索一命中就把用户消息顶替掉了。官方 DefaultContentInjector 的模板是
 *   "{{userMessage}}\n\nAnswer using the following information:\n{{contents}}"，返回值仍是 UserMessage。
 * 想要"文档优先级更高"，应该在模板文字里把要求写强，而不是在这里换掉用户消息。
 *
 * 调用方：StreamingRagAssistantConfig 的 chatRequestTransformer。
 * 返回值只影响本次请求，不写进会话历史（实测 2026-10-09）：
 *   记忆里存的是"维森·安防系列主要有哪些功能？"，发出去的是它 + 文档正文 + 指示。
 *
 * 不要改回 .retrievalAugmentor(...)：那条路会把返回值当作那条用户消息写进 ChatMemory，
 * 文档被固化进历史并每轮重发（实测那条消息约 1045 字符），既重复计费又挤占 20 条的窗口。
 */
@Component
public class RagContentInjector implements ContentInjector {

    /**
     * 注入模板。
     * {{userMessage}} 必需，是用户原始问题；{{contents}} 是检索到的文档片段（多段用 --- 分隔）。
     * 两者缺一不可：只留 contents 就等于把问题删了。
     */
    private static final PromptTemplate PROMPT_TEMPLATE = PromptTemplate.from("""
            {{userMessage}}

            以下是从知识库中检索到的相关文档内容，请优先基于这些信息来回答上面的问题:

            {{contents}}

            请注意:
            1. 优先使用上述文档中的信息来回答
            2. 如果文档中没有相关信息，可以使用你的通用知识
            3. 如果不确定答案，请明确告知用户
            """);

    /**
     * 执行注入。
     * 检索结果为空 → 原样返回（走正常对话，不做 RAG）。
     * 非 UserMessage → 原样返回（契约只支持 UserMessage，别把内容弄丢）。
     */
    @Override
    public ChatMessage inject(List<Content> contents, ChatMessage chatMessage) {
        if (contents == null || contents.isEmpty()) {
            return chatMessage;  // 无检索结果，不做 RAG 增强
        }
        if (!(chatMessage instanceof UserMessage userMessage)) {
            // 框架只对 UserMessage 调用注入；真出现别的类型，宁可原样返回也不要丢内容
            System.out.println("⚠ RAG 注入跳过：消息类型不是 UserMessage，实际=" + chatMessage.type());
            return chatMessage;
        }

        // 拼接多个文档片段，用 --- 分隔
        String formattedContents = contents.stream()
                .map(content -> content.textSegment().text())
                .collect(Collectors.joining("\n\n---\n\n"));

        Map<String, Object> variables = new HashMap<>();
        variables.put("userMessage", userMessage.singleText());   // ★ 原问题必须带上
        variables.put("contents", formattedContents);
        Prompt prompt = PROMPT_TEMPLATE.apply(variables);

        // 保持 UserMessage 类型：把增强后的整段文本并回用户消息
        // （与官方 DefaultContentInjector 的做法一致；返回 SystemMessage 会顶替掉用户消息）
        return userMessage.toBuilder()
                .contents(List.of(TextContent.from(prompt.text())))
                .build();
    }
}
