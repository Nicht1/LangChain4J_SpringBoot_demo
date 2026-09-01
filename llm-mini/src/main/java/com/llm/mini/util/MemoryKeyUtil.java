package com.llm.mini.util;

/**
 * 复合 memoryId 工具。
 * <p>
 * 单一 key 同时驱动三条隔离链，保证"对应 agent 读对应历史 / 对应 RAG"：
 * <pre>
 * memoryId = {userId}:{agentId}:{sessionId}
 *
 *   ├─ systemMessageProvider(memoryId)   → 拆出 userId+agentId → 归属校验后取该 agent 的 SYSTEM_MESSAGE
 *   ├─ chatMemoryProvider.getMemory(memoryId) → 以 memoryId 为 key 存/取 MySQL 历史 → 按 用户×智能体×会话 隔离
 *   └─ @V("userId")/@V("agentId")        → 进 RAG Query.invocationParameters → Milvus 按 userId+agentId 过滤
 * </pre>
 * 重启服务后：agent 配置 / 历史 / 向量都在 MySQL / Milvus，不丢失；
 * 内存中 assistant 缓存只是按 memoryId 懒重建，不承担任何持久化职责。
 */
public final class MemoryKeyUtil {

    private MemoryKeyUtil() {
        // 工具类，禁止实例化
    }

    /** 构建复合 memoryId：{userId}:{agentId}:{sessionId} */
    public static String build(Long userId, Long agentId, String sessionId) {
        return userId + ":" + agentId + ":" + sessionId;
    }

    /** 从复合 memoryId 取出 userId */
    public static Long userIdOf(String memoryKey) {
        return Long.valueOf(memoryKey.split(":")[0]);
    }

    /** 从复合 memoryId 取出 agentId */
    public static Long agentIdOf(String memoryKey) {
        return Long.valueOf(memoryKey.split(":")[1]);
    }

    /** 从复合 memoryId 取出原始 sessionId */
    public static String sessionIdOf(String memoryKey) {
        return memoryKey.split(":")[2];
    }
}
