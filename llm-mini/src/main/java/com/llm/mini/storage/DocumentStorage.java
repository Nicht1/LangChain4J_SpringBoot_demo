package com.llm.mini.storage;

import java.io.InputStream;

/**
 * 文档存储抽象 —— 客户上传的原始文档存哪里。
 *
 * 当前只有一个实现：AliyunOssDocumentStorage。客户上传的文档只能进 OSS，
 * 不落应用本地磁盘；本地资源目录只服务官方内置知识库（那条路见 EmbeddingStoreConfig，
 * 不经过本接口）。
 *
 * 保留接口是为了留一道缝：换 MinIO/S3 只需再加实现，摄入逻辑不动；
 * 也方便测试时塞替身。
 *
 * 摄入只需要字节流，不关心字节从哪来（已实测 DocumentParser.parse(InputStream)
 * 不落盘也能解析 PDF/docx）。
 *
 * key 由调用方生成，形如 {ownerId}/{agentId}/{docId}/{fileName}。
 * key 里带 docId，不同用户上传同名文件不会互相覆盖。
 *
 * 实现约定：
 * - open 返回的流由调用方关闭
 * - delete 对不存在的对象要幂等成功
 * - 失败统一抛 StorageException
 */
public interface DocumentStorage {

    /** 保存；size 未知传 -1。返回实际写入的 key */
    String save(String key, InputStream in, long size);

    /** 打开读取流，调用方负责关闭 */
    InputStream open(String key);

    /** 删除，不存在时幂等成功 */
    void delete(String key);

    /** 存在性检查 */
    boolean exists(String key);

    /** 存储类型标识（local / oss） */
    String type();

    /** 存储层异常 */
    class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }

        public StorageException(String message) {
            super(message);
        }
    }
}
