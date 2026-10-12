package com.llm.mini.rag;

/**
 * 上传文档的摄入状态。
 *
 * PENDING(排队) → INDEXING(处理中) → READY(可检索)
 *                                ↘ FAILED(可重试)
 *
 * 用枚举的理由：状态更新方法的入参就是它，拼错在编译期就报错；
 * canRetry / isTerminal 把状态判断收在状态自己身上。
 * 实体字段仍用 String（写入 .name()，读取 parse()），
 * 避免引入 MyBatis-Plus 全局枚举 TypeHandler（会影响项目里其它枚举）。
 */
public enum DocumentStatus {

    /** 已落表，异步任务排队中 */
    PENDING,

    /** 正在解析 + 分片 + 向量化 */
    INDEXING,

    /** 摄入成功，可被检索 */
    READY,

    /** 摄入失败，可重试 */
    FAILED;

    /** 解析字符串；认不出返回 null——不编造状态，否则上层会误判 */
    public static DocumentStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 已到终态（不会自己再变） */
    public boolean isTerminal() {
        return this == READY || this == FAILED;
    }

    /** 后台正在处理，还没出结果 */
    public boolean isBusy() {
        return this == PENDING || this == INDEXING;
    }

    /** 只允许 FAILED 重试：READY 重摄入会产生重复向量，PENDING/INDEXING 会并发摄入同一文档 */
    public boolean canRetry() {
        return this == FAILED;
    }
}
