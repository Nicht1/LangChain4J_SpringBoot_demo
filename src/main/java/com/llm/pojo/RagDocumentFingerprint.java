package com.llm.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * RAG 文档指纹表：记录每个文档的内容哈希，用于增量同步判断
 */
@Data
@Accessors(chain = true)
@TableName("rag_document_fingerprint")
public class RagDocumentFingerprint {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文档相对路径（相对于 document-path，如 公司简介.txt） */
    private String filePath;

    /** 文件内容的 SHA-256 哈希 */
    private String contentHash;

    /** 上次同步时间 */
    private LocalDateTime updatedAt;
}
