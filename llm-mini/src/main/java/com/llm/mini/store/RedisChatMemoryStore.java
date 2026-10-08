package com.llm.mini.store;

import com.llm.mini.config.ChatMemoryProperties;
import com.llm.mini.util.LuaScriptLoader;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 基于 Redis List 的会话记忆存储（默认后端）。
 *
 * <h2>为什么用 List + LTRIM</h2>
 * 会话记忆在数据形态上就是"<b>一个按时间有序、有长度上限的队列</b>"，这正好是
 * Redis List 天然的表达能力。于是"只保留最近 N 条"不需要应用层写任何修剪逻辑，
 * 交给 {@code LTRIM} 一条命令即可（服务端 O(1) 摊销裁剪）；而"追加 / 覆盖"也不需要
 * 任何 diff——因为 {@code ChatMemoryStore.updateMessages} 本来就是<b>全量语义</b>：
 * LangChain4j 的 {@code MessageWindowChatMemory} 每次 {@code add()} 之后都会把
 * <b>当前完整窗口</b>（≤ maxMessages 条，头部已淘汰）传进来。
 *
 * <h2>写入：一条 Lua 脚本 = 1 次往返</h2>
 * 脚本本体在 {@code src/main/resources/lua/chat_memory_write.lua}，不在 Java 里内联。
 * 这样它可以被 {@code redis-cli --eval} 直接拿去对着真 Redis 验证边界，
 * 不必先起 Spring、也不必先跑通 Java 调用链；KEYS/ARGV 契约也写在脚本头部，改脚本时不会漏看。
 * <pre>
 * DEL   key                    -- 清掉旧快照
 * RPUSH key msg1 .. msgN       -- 按时间正序整窗口写入（头=最旧，尾=最新）
 * LTRIM key -N -1              -- 只保留最新的 N 条
 * EXPIRE key ttl               -- 滑动过期，避免空会话永久占 key
 * </pre>
 * 包在 Lua 里执行，保证 <b>原子</b>（读侧永远不会看到被清空的中间态），且只有
 * <b>1 次网络往返</b>。因为 Redis 单条命令/Lua 脚本本身原子，这里<b>不需要</b>任何
 * 本地锁——这一点比 MySQL 行级方案（要自己分段锁 + 摊销修剪）简单得多。
 *
 * <h2>为什么整窗口覆盖，而不是"只追加新增的那条"</h2>
 * 想只追加一条，就得先算出"哪条是新的"，而两条路都不干净：
 * <ul>
 *   <li><b>按条数推</b>：窗口写满后 {@code LLEN} 恒等于 max，无法判断窗口是否滑动过，
 *       会漏写最新消息。</li>
 *   <li><b>按内容比对</b>：用户连发两条相同内容时无法消歧（需要额外引入序号列）。</li>
 * </ul>
 * 而整窗口覆盖的代价只是每次多写不到 20 个小字符串——Redis 上不到 100 微秒，
 * 相对"一次 LLM 调用要几秒"完全可以忽略。用一点写入放大换掉全部对位逻辑，
 * 这个交换是划算的。
 *
 * <h2>读取</h2>
 * {@code LRANGE key 0 -1} 拿回整个窗口，天然就是时间正序（头=最旧），
 * 不需要像 LPUSH 方案那样再反转一次。之后过一遍
 * {@link ChatMessageWindowSanitizer} 剔除窗口边界上的孤儿工具消息。
 *
 * <h2>并发说明</h2>
 * 单次写入是原子的，不会写出半截窗口。但"读窗口 → 调 LLM → 写窗口"这一整个回合
 * 不是原子的，同一会话的并发请求仍然是<b>后写覆盖先写</b>。这属于会话级并发控制问题，
 * 需要在服务层按 memoryId 串行化（或前端保证同一会话不并发发问），存储层无法解决。
 */
@Repository
@ConditionalOnProperty(name = "llm.memory.store", havingValue = "redis", matchIfMissing = true)
public class RedisChatMemoryStore implements ChatMemoryStore {

    /** 写入脚本的 classpath 位置（与 profile / 环境无关，始终打包在同一位置） */
    private static final String WRITE_SCRIPT_PATH = "lua/chat_memory_write.lua";

    /**
     * 写入脚本。返回值声明为 {@code Long}（脚本最后 return 的是 LLEN 整数），
     * 若声明成 Object 会走值反序列化，整数会被当成 JSON 解析而报错。
     * 脚本在 {@link LuaScriptLoader} 里按 UTF-8 读取并缓存；读不到会直接抛异常，
     * 让问题在启动期暴露，而不是运行期悄悄用错脚本。
     */
    private static final RedisScript<Long> WRITE_SCRIPT =
            LuaScriptLoader.load(WRITE_SCRIPT_PATH, Long.class);

    private final StringRedisTemplate redis;
    private final ChatMemoryProperties properties;

    public RedisChatMemoryStore(StringRedisTemplate redis, ChatMemoryProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        // 头=最旧、尾=最新，LRANGE 0 -1 直接就是时间正序，无需 reverse
        List<String> payloads = redis.opsForList().range(keyOf(memoryId), 0, -1);
        if (payloads == null || payloads.isEmpty()) {
            return new ArrayList<>();
        }
        List<ChatMessage> messages = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            try {
                messages.add(ChatMessageDeserializer.messageFromJson(payload));
            } catch (Exception e) {
                // 单条脏数据不拖垮整次读取；丢了这一条也只是少一轮上下文
                System.err.println("⚠ 反序列化记忆消息失败, 已跳过: " + e.getMessage());
            }
        }
        return ChatMessageWindowSanitizer.sanitize(messages);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messageList) {
        if (memoryId == null || messageList == null || messageList.isEmpty()) {
            return;
        }
        int max = properties.getMaxMessages();

        // 防御性裁剪：框架正常只传 ≤ 窗口上限的消息，但不能依赖调用方
        List<ChatMessage> window = messageList.size() <= max
                ? messageList
                : messageList.subList(messageList.size() - max, messageList.size());

        List<String> payloads = new ArrayList<>(window.size());
        for (ChatMessage message : window) {
            try {
                payloads.add(ChatMessageSerializer.messageToJson(message));
            } catch (Exception e) {
                System.err.println("⚠ 序列化记忆消息失败, 已跳过: " + e.getMessage());
            }
        }
        if (payloads.isEmpty()) {
            return;
        }

        String key = keyOf(memoryId);
        Long length = redis.execute(WRITE_SCRIPT, Collections.singletonList(key), buildArgs(payloads, max));
        System.out.println("💾 写入记忆窗口 -> " + key + " 条数=" + length);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String key = keyOf(memoryId);
        redis.delete(key);
        System.out.println("🗑️ 删除会话记忆: " + key);
    }

    /** 组装脚本参数：ARGV[1]=上限, ARGV[2]=TTL 秒, ARGV[3..]=消息 JSON（StringRedisTemplate 要求参数为 String） */
    private Object[] buildArgs(List<String> payloads, int max) {
        Object[] args = new Object[payloads.size() + 2];
        args[0] = String.valueOf(max);
        args[1] = String.valueOf(properties.getRedis().ttlSeconds());
        for (int i = 0; i < payloads.size(); i++) {
            args[i + 2] = payloads.get(i);
        }
        return args;
    }

    private String keyOf(Object memoryId) {
        // memoryId 形如 userId:agentId:sessionId，键里含冒号是 Redis 常规用法（层级命名）
        return properties.getRedis().getKeyPrefix() + memoryId;
    }
}
