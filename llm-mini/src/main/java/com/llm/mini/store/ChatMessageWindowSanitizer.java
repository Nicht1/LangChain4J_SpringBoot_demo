package com.llm.mini.store;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话窗口修正（与存储后端无关，Redis / MySQL 两个实现共用）。
 *
 * <h2>为什么需要</h2>
 * 滑动窗口按<b>条数</b>硬切，很容易切在工具调用链中间。OpenAI 兼容接口对这类消息是
 * <b>硬校验</b>：{@code tool} 消息必须是对前面某个带 {@code tool_calls} 的 assistant
 * 消息的回应，否则直接 400：
 * <pre>
 * Messages with role 'tool' must be a response to a preceding message with 'tool_calls'
 * </pre>
 * 所以窗口开头那些"没有前置 assistant 的 {@code tool} 结果"必须剔掉。
 *
 * <h2>⚠️ 只剔头部，绝不剔尾部（这是个踩过的坑）</h2>
 * 曾经这里还有一条规则：<i>"剔除结尾那个只发起工具调用、却没有执行结果的 AiMessage"</i>。
 * 它看起来合理（这种消息确实会让接口 400），但会<b>直接破坏工具调用回合</b>。
 * <p>
 * 原因：{@code MessageWindowChatMemory.messages()} <b>每次调用都会回读一次存储</b>
 * （已用 {@code ChatMemoryReloadProbe} 实测：连调 3 次 messages() 触发 3 次
 * {@code ChatMemoryStore.getMessages}）。而工具调用的执行过程恰好是：
 * <pre>
 * add(userMessage)
 *   → 调模型，拿到带 toolCalls 的 AiMessage
 *   → add(aiMessageWithToolCalls)     ← 此时窗口最后一条就是它
 *   → 【框架再次取 messages() 发起第 2 次请求】 ← 回读 + 修正 → 锚点被删
 *   → add(toolExecutionResultMessage)
 * </pre>
 * 结果第 2 次请求里 {@code tool} 消息失去了前置的 {@code tool_calls}，
 * 真实 API 直接 400（本仓库实测复现过）。
 * <p>
 * 换句话说：<b>尾部那条 AiMessage 在回合中途是"合法且必需"的</b>，只有"上一轮崩溃导致
 * 工具结果永远没产生"时才是脏数据。用"每轮都删"的代价去防那种罕见情况，得不偿失。
 * 真正出现悬挂工具调用时，清掉该会话记忆即可（{@code /api/stream/chat/clear}）。
 */
public final class ChatMessageWindowSanitizer {

    private ChatMessageWindowSanitizer() {
        // 工具类，禁止实例化
    }

    /**
     * 剔除窗口开头的孤儿工具结果消息（它们没有前置的 assistant tool_calls，接口会 400）。
     * <p>
     * 只动头部：头部之前的消息必然不在窗口里，所以这里的 {@code tool} 结果一定是孤儿。
     * 尾部不做任何处理——见类注释。
     *
     * @param messages 时间正序的窗口消息
     * @return 修正后的窗口；无需修正时原样返回
     */
    public static List<ChatMessage> sanitize(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return messages;
        }
        int from = 0;
        while (from < messages.size() && messages.get(from) instanceof ToolExecutionResultMessage) {
            from++;
        }
        if (from == 0) {
            return messages;
        }
        System.out.println("✂ 窗口修正: 剔除头部孤儿工具结果 " + from + " 条");
        return new ArrayList<>(messages.subList(from, messages.size()));
    }
}
