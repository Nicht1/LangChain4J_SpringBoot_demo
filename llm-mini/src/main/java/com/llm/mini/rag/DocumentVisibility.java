package com.llm.mini.rag;

/**
 * 文档可见范围（混合隔离粒度）。
 *
 * AGENT：只有上传时那个智能体能检索到
 * USER ：该用户名下所有智能体都能检索到
 *
 * 两者都不跨用户——userId 是硬约束，visibility 只在同一用户内部决定要不要放宽 agentId。
 * 摄入时写进向量的 metadata.visibility；检索时的过滤表达式见 RagContentRetriever。
 */
public enum DocumentVisibility {

    /** 仅上传时那个智能体可见 */
    AGENT,

    /** 该用户全部智能体可见 */
    USER;

    /** 解析；非法或空值回落到最保守的 AGENT（宁可少看见，不可越权暴露） */
    public static DocumentVisibility parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return AGENT;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AGENT;
        }
    }
}
