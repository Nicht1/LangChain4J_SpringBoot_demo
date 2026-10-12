package com.llm.mini.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.ObjectMetadata;
import com.llm.mini.config.RagUploadProperties;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * 阿里云 OSS 存储实现 —— 客户上传文档的唯一出口。
 *
 * 客户上传的原始文档只存 OSS，不落应用本地磁盘；本地资源目录只服务官方内置知识库
 * （那条路走 EmbeddingStoreConfig，与本类无关）。
 *
 * 只做字节搬运：归属校验、去重、状态机都在 RagDocumentService。
 *
 * putObject 必须带 contentLength：SDK 在长度未知时会先把整个流缓冲进内存，
 * 20MB 的文件会直接顶到堆内存。上传入口是 MultipartFile，长度天然已知。
 *
 * OSS 配置项 llm.rag.upload.oss.* 默认是示例占位值，真实密钥请走环境变量，
 * 不要提交进 Git。仍是占位值时启动会打 WARN。
 */
@Slf4j
@Component
public class AliyunOssDocumentStorage implements DocumentStorage {

    private final OSS client;
    private final String bucketName;

    /** 对象 key 前缀，由本类统一拼上，业务层就不用知道存储命名空间（换 MinIO/本地也不用改） */
    private final String keyPrefix;

    /**
     * 存储是否可用。
     * 构造期不抛异常是刻意的：OSS 只是"客户上传文档"一件事的依赖，
     * 配置写错不该连累聊天和官方内置知识库。所以降级成"存储不可用"，
     * 由上传接口返回明确错误，启动只打 ERROR 日志。
     */
    private volatile boolean available;
    private volatile String unavailableReason;

    public AliyunOssDocumentStorage(RagUploadProperties props) {
        RagUploadProperties.Oss oss = props.getOss();
        this.bucketName = oss.getBucketName();
        this.keyPrefix = (oss.getKeyPrefix() == null || oss.getKeyPrefix().isBlank())
                ? "" : oss.getKeyPrefix().replaceAll("/+$", "") + "/";
        OSS built = null;
        // 先判配置完整性：不全就别建客户端。
        // 否则会拿空凭据去连，报一个看不懂的 InvalidAccessKeyId —— 明明问题是"没配"。
        List<String> missing = oss.missingRequired();
        if (!missing.isEmpty()) {
            this.unavailableReason = "未配置 " + missing + "（llm.rag.upload.oss.*）";
            log.error("[llm.rag.upload] 阿里云 OSS 不可用：{}。客户上传文档会失败，"
                    + "聊天与内置知识库不受影响", unavailableReason);
            this.client = null;
            return;
        }

        try {
            built = new OSSClientBuilder().build(
                    oss.getEndpoint(), oss.getAccessKeyId(), oss.getAccessKeySecret());
            // 用 listObjects 探活：它会真正带凭据发一次请求，同时校验 AK 有效、bucket 存在、读权限。
            // 不能用 doesBucketExist —— 2026-10-12 实测：AK 无效时它仍返回 true
            //（只要 bucket 没被报成"不存在"），于是"凭据是错的"会被误判成"就绪"，
            // 错误一直拖到用户上传时才暴露。
            // 注意：该探测需要 ListBucket 权限；若你的 AK 只授了 PutObject/GetObject，
            // 这里会报不可用但实际能上传——此时可把 available 的判断去掉，靠 save 的异常报错。
            built.listObjects(bucketName, oss.getKeyPrefix());
            this.available = true;
            log.info("[llm.rag.upload] 阿里云 OSS 就绪: endpoint={} bucket={} keyPrefix={}",
                    oss.getEndpoint(), bucketName, oss.getKeyPrefix());
        } catch (Exception e) {
            this.unavailableReason = e.getMessage();
            log.error("[llm.rag.upload] 阿里云 OSS 不可用（{}）。客户上传文档会失败，"
                    + "聊天与内置知识库不受影响。请检查 llm.rag.upload.oss.* 配置", e.getMessage());
        }
        this.client = built;
    }

    /** 不可用时抛出可读错误，避免上层拿到 NPE */
    private void ensureAvailable() {
        if (!available) {
            throw new DocumentStorage.StorageException(
                    "OSS 存储不可用，客户上传文档失败（原因：" + unavailableReason
                            + "）。请检查 llm.rag.upload.oss.* 配置");
        }
    }

    /** 拼上命名空间前缀并做基本校验（去掉可能的 ../ 片段，防越出前缀） */
    private String fullKey(String key) {
        if (key == null || key.isBlank()) {
            throw new DocumentStorage.StorageException("存储 key 不能为空");
        }
        String clean = key.replace("\\", "/").replaceAll("(^|/)\\.\\.(/|$)", "/").replaceAll("^/+", "");
        if (clean.isBlank()) {
            throw new DocumentStorage.StorageException("非法的存储 key: " + key);
        }
        return keyPrefix + clean;
    }

    @Override
    public String save(String key, InputStream in, long size) {
        ensureAvailable();
        if (size <= 0) {
            throw new DocumentStorage.StorageException(
                    "OSS 写入必须提供内容长度（避免 SDK 把大文件全量缓冲进内存），实际=" + size);
        }
        String objectKey = fullKey(key);
        try {
            ObjectMetadata meta = new ObjectMetadata();
            meta.setContentLength(size);
            client.putObject(bucketName, objectKey, in, meta);
            return key;
        } catch (Exception e) {
            throw new DocumentStorage.StorageException("OSS 写入失败: " + objectKey + " -> " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream open(String key) {
        ensureAvailable();
        String objectKey = fullKey(key);
        try {
            return client.getObject(bucketName, objectKey).getObjectContent();
        } catch (Exception e) {
            throw new DocumentStorage.StorageException("OSS 读取失败: " + objectKey + " -> " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String key) {
        ensureAvailable();
        String objectKey = fullKey(key);
        try {
            client.deleteObject(bucketName, objectKey);
        } catch (Exception e) {
            throw new DocumentStorage.StorageException("OSS 删除失败: " + objectKey + " -> " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(String key) {
        if (!available) {
            return false;
        }
        try {
            return client.doesObjectExist(bucketName, fullKey(key));
        } catch (Exception e) {
            log.warn("OSS 存在性检查失败（按不存在处理）: {} -> {}", key, e.getMessage());
            return false;
        }
    }

    @Override
    public String type() {
        return "oss";
    }

    /** 客户端持有 HTTP 连接池，进程退出时显式释放 */
    @PreDestroy
    public void shutdown() {
        if (client == null) {
            return;
        }
        try {
            client.shutdown();
            log.info("[llm.rag.upload] 阿里云 OSS 客户端已关闭");
        } catch (Exception e) {
            log.warn("关闭 OSS 客户端异常: {}", e.getMessage());
        }
    }
}
