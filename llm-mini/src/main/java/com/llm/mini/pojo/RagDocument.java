package com.llm.mini.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 用户上传的 RAG 文档（多租户知识库）。
 * <p>
 * 这是"上传文档"这条链路的<b>真相来源</b>：原文件在存储层（本地/OSS），向量在 Milvus，
 * 而"这份文档属于谁、谁可见、摄入到哪一步了"全部记在这张表里。
 * 与内置知识库的区别：内置知识库以<b>目录</b>为真相来源（启动扫描对账），
 * 上传文档以<b>本表</b>为真相来源（上传即摄入，启动不扫描）。
 * <p>
 * 建表脚本见 {@code resources/sql/rag_document.sql}。
 */
@Data
@Accessors(chain = true)
@TableName("rag_document")
public class RagDocument {

    /** 文档 ID（UUID）。同时是向量锚点 {@code doc_source} 的组成部分，删向量时按它定位 */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 归属用户 ID（多租户隔离第一维） */
    private Long ownerId;

    /** 上传时所属智能体 ID（溯源用） */
    private Long agentId;

    /** 可见范围，取值见 {@link com.llm.mini.rag.DocumentVisibility} */
    private String visibility;

    /** 原始文件名（仅展示，落盘名用 docId 避免同名覆盖） */
    private String fileName;

    /** 扩展名（小写、不含点） */
    private String fileExt;

    /** 客户端上报的 MIME（仅参考，真实类型由 Tika 探测） */
    private String contentType;

    /** 文件字节数 */
    private Long fileSize;

    /** 文件内容 SHA-256，同一 owner 下去重用 */
    private String contentHash;

    /** 存储层对象 key（本地相对路径 或 OSS object key） */
    private String storageKey;

    /** 向量锚点：{@code rag://{ownerId}/{agentId}/{id}} */
    private String docSource;

    /** 摄入状态，取值见 {@link com.llm.mini.rag.DocumentStatus} */
    private String status;

    /** 写入 Milvus 的分片数（READY 时有意义） */
    private Integer chunkCount;

    /** 失败原因（status=FAILED 时） */
    private String failReason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
