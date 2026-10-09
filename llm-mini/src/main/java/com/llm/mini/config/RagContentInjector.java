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
 * RAG 内容注入器：把检索到的文档片段并入<b>用户消息</b>，供 LLM 参考。
 *
 * <h2>⚠️ 契约要点（改这里之前务必先读）</h2>
 * {@link ContentInjector} 的语义是"把 contents 注入<b>到给定的 UserMessage 里</b>"：
 * <pre>
 * ChatMessage inject(List&lt;Content&gt; contents, ChatMessage chatMessage);
 * </pre>
 * 官方 javadoc 明确写着 <i>"Injects given Contents into a given UserMessage …
 * the ChatMessage <b>into which</b> the Contents are to be injected.
 * Currently, only UserMessage is supported."</i>
 * <p>
 * 也就是说：<b>这个返回值会替换掉请求里的那条用户消息</b>（见
 * {@code DefaultRetrievalAugmentor.augment()} —— 它把 inject 的返回值直接作为
 * {@code AugmentationResult.chatMessage()}）。因此：
 * <ul>
 *   <li><b>必须把原始用户消息带上</b>（模板里的 {@code {{userMessage}}}），
 *       否则用户的问题会被整条丢掉，模型只能对着文档空谈、答非所问；</li>
 *   <li><b>必须返回 UserMessage</b>。曾经这里返回的是
 *       {@code SystemMessage.from(prompt.text())}，且模板只有 {@code {{contents}}}——
 *       结果是"检索一旦命中，用户消息就被一条只含文档的 SystemMessage 顶替"，
 *       问题丢失。这与官方默认实现 {@code DefaultContentInjector} 的行为不一致
 *       （它的模板是 {@code {{userMessage}}\n\nAnswer using the following information:\n{{contents}}}，
 *       返回值是 {@code userMessage.toBuilder().contents(...)}，仍是 UserMessage）。</li>
 * </ul>
 * 如果想要"文档优先级更高"的效果，正确做法是<b>在模板文字里把要求写强</b>，
 * 或者另用 {@code chatRequestTransformer} 追加一条 SystemMessage，
 * <b>而不是</b>在这里把用户消息换掉。
 *
 * <h2>注入时机</h2>
 * 在 ChatModel 被调用之前，由 LangChain4j 的 RAG 框架自动触发（需要检索命中，
 * 即相似度 ≥ {@code minScore}，见 {@code RagContentRetriever}）。
 *
 * <h2>调用方与"不入历史"的保证</h2>
 * 本类由 {@code StreamingRagAssistantConfig} 的 <b>{@code chatRequestTransformer} 直接调用</b>，
 * 返回值只用于替换"本次即将发出的请求"里那条用户消息。
 * <pre>
 * 记忆里存的：[USER] 维森·安防系列主要有哪些功能？        ← 原始问题，干净
 * 发出去的是：[USER] 维森·安防系列主要有哪些功能？ + 检索到的文档正文 + 指示
 * </pre>
 * <b>已被实测验证</b>（2026-10-09，Redis 记忆后端）。
 * <p>
 * ⚠️ <b>不要改回 {@code .retrievalAugmentor(...)}</b>：那条路会把本方法的返回值
 * <b>当作那条用户消息写进 ChatMemory</b>——实测后果是检索到的文档被固化进历史、
 * 之后每轮重复发送（那条消息 JSON 长约 1045 字符），既重复计费又挤占只有 20 条的会话窗口。
 */
@Component
public class RagContentInjector implements ContentInjector {

    /**
     * 注入模板。
     * <p>
     * {@code {{userMessage}}} —— <b>必需</b>，用户原始问题；
     * {@code {{contents}}} —— 检索到的文档片段（多段用 --- 分隔）。
     * 二者缺一不可：只留 contents 就等于把问题删了。
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
     * <p>
     * 检索结果为空 → 原样返回（走正常对话，不做 RAG 增强）。
     * 非 UserMessage → 原样返回（契约目前只支持 UserMessage，别把内容弄丢）。
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
