package com.llm.mini.rag;

/**
 * 写入 Milvus 向量的元数据 key。摄入侧和检索侧必须完全一致，所以集中定义。
 *
 * 原来这些 key 以字符串字面量散落在 3 个文件 13 处，任一处拼错都不报错——
 * 编译能过、运行不抛异常，只是过滤匹配不到东西，表现为检索静默失效，甚至隔离失效导致越权可读。
 * 抽成常量后写错名字会直接编译失败。
 *
 * 注意：Milvus 里已写入的向量带的是旧 key，改名不会自动迁移，必须重新摄入全部文档。
 */
public final class RagMetadataKeys {

    /** 归属用户，多租户隔离硬约束 */
    public static final String USER_ID = "userId";

    /** 归属智能体（上传时那个） */
    public static final String AGENT_ID = "agentId";

    /** 可见范围，DocumentVisibility 的 name */
    public static final String VISIBILITY = "visibility";

    /** 文档锚点 rag://{ownerId}/{agentId}/{docId}，删向量时按它定位 */
    public static final String DOC_SOURCE = "doc_source";

    /** 原始文件名，仅排查用 */
    public static final String FILE_NAME = "file_name";

    private RagMetadataKeys() {
    }
}
