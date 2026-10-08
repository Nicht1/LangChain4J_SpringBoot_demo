package com.llm.mini.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 聊天记忆容量与后端配置（前缀 {@code llm.memory}）。
 *
 * <h2>后端选择</h2>
 * 由 {@link #store} 决定（{@code redis} 默认 / {@code mysql}）：
 * <ul>
 *   <li><b>redis</b> —— 推荐。会话记忆天然是"最近 N 条有序队列"，用 Redis List 一条
 *       Lua 脚本（{@code DEL + RPUSH + LTRIM}）就能完成"整窗口覆盖 + 只保留最近 N 条"，
 *       原子、单次往返、无需 diff、无需索引、无需本地锁。</li>
 *   <li><b>mysql</b> —— 仅当需要按消息查询/审计/多端同步时使用。行级表 + 摊销修剪，
 *       配套索引脚本见 {@code resources/sql/chat_memory_index.sql}。</li>
 * </ul>
 *
 * <h2>两个容量参数的分工（仅 mysql 后端使用 {@link #trimInterval}）</h2>
 * <ul>
 *   <li>{@link #maxMessages} —— <b>语义上限</b>。内存窗口大小，也是持久层修剪后严格保留的条数，
 *       更是读取时取回的条数。三者必须一致，否则窗口与持久化会错位。</li>
 *   <li>{@link #trimInterval} —— <b>摊销间隔</b>。累计写入达到该条数才做一次批量修剪。
 *       注意它是"写了多少条"，不是"表里有多少行"——按写入量触发就不必每次写入都去
 *       {@code COUNT(*)}。代价是 MySQL 行数会在
 *       {@code [maxMessages, maxMessages + trimInterval]} 区间内浮动（这是有界的）。</li>
 * </ul>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "llm.memory")
public class ChatMemoryProperties {

    /** 记忆后端：redis（默认，推荐）| mysql */
    private String store = "redis";

    /** 语义上限：内存窗口大小 = 持久层保留条数 = 读取条数 */
    private int maxMessages = 20;

    /** 摊销间隔（仅 mysql 后端）：累计写入该条数后触发一次批量修剪，DB 行数上界 2×窗口 */
    private int trimInterval = 20;

    /** Redis 后端参数 */
    private Redis redis = new Redis();

    @Data
    public static class Redis {

        /** 键前缀，最终键为 {@code prefix + memoryId}（memoryId = userId:agentId:sessionId） */
        private String keyPrefix = "llm:chat:memory:";

        /**
         * 滑动过期时间：每次写入刷新。空会话在最后一次对话后该时长自动回收，
         * 避免"聊过一次就永久占一个 key"。设为 0 表示永不过期。
         */
        private Duration ttl = Duration.ofDays(7);

        /** TTL 秒数；<= 0 表示不设置过期 */
        public long ttlSeconds() {
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                return 0L;
            }
            return ttl.toSeconds();
        }
    }

    /**
     * 配置自愈：非法值回落到安全默认，避免因配置写错导致
     * "修剪间隔 ≤ 0"（永不修剪，DB 无界增长）或"窗口为 0"（框架直接报错）。
     * <p>
     * 顺带把<b>最终生效值</b>打出来。原因：本类的字段都有默认值，而默认值恰好与
     * application.yaml 里写的值相同（20 / 20 / redis / 7d），所以"启动没报错"并不能
     * 说明 YAML 生效了——若键名拼错（例如写成 {@code max-message}），Spring 的宽松绑定
     * 会<b>静默忽略</b>它，字段保持默认值，从外部看毫无异常。
     * 打出生效值后，直接和 YAML 对照即可发现；需要强校验时应改用非默认值验证或引入配置校验。
     */
    @PostConstruct
    void normalize() {
        if (maxMessages <= 0) {
            maxMessages = 20;
        }
        if (trimInterval <= 0) {
            trimInterval = maxMessages;
        }
        if (redis == null) {
            redis = new Redis();
        }
        log.info("[llm.memory] 生效配置: store={} maxMessages={} trimInterval={} "
                        + "redisKeyPrefix={} redisTtlSeconds={}(0=永不过期)",
                store, maxMessages, trimInterval, redis.getKeyPrefix(), redis.ttlSeconds());
    }
}
