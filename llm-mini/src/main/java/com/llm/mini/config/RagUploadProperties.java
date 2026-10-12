package com.llm.mini.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户上传文档相关配置（前缀 llm.rag.upload）。
 *
 * 客户上传的文档只能存阿里云 OSS，不落应用本地磁盘（本地资源目录只服务官方内置知识库）。
 * 所以这里没有存储后端开关，OSS 是唯一出口。
 *
 * OSS 的 endpoint / accessKeyId / accessKeySecret / bucketName 都是示例占位值，
 * 直接用会连不上，也绝不要把真实密钥提交进 Git。建议用环境变量注入：
 *
 *   llm:
 *     rag:
 *       upload:
 *         oss:
 *           access-key-id: ${OSS_ACCESS_KEY_ID}
 *           access-key-secret: ${OSS_ACCESS_KEY_SECRET}
 *           bucket-name: my-rag-bucket
 *
 * 占位符要带默认值（${ENV:xxx}）——只写 ${ENV} 时环境变量没设置会导致启动失败。
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "llm.rag.upload")
public class RagUploadProperties {

    /**
     * 单文件大小上限，默认 20MB。用 DataSize 而不是 long，是为了 yaml 里能写 "20MB"
     * （声明成 long 时写 "20MB" 会绑定失败）。
     *
     * 这个值是**单一来源**：容器层的 spring.servlet.multipart.max-file-size 用占位符
     * 引用了它（见 application.yaml），所以不会出现"容器限制和应用层校验不一致"。
     * 注意容器还有 max-request-size（默认 10MB），改大本值时记得一并调整。
     */
    private DataSize maxFileSize = DataSize.ofMegabytes(20);

    /**
     * 允许上传的扩展名（小写、不含点）。
     * 第一道过滤，快速拒绝明显不支持的格式；真实类型由 Tika 按内容探测，
     * 所以扩展名与内容不符也不会出安全问题（最多解析失败置 FAILED）。
     */
    private List<String> allowedExtensions = List.of(
            "pdf", "docx", "doc", "txt", "md", "markdown",
            "html", "htm", "xlsx", "xls", "pptx", "ppt", "csv");

    /** 单个用户允许的文档数上限（防一个人把 OSS 和向量库吃满） */
    private int maxDocumentsPerUser = 200;

    /** 单条文档的分片长度（字符）——太小会碎片化，太大会降低检索精度 */
    private int chunkSize = 300;

    /** 相邻分片重叠字符数（避免答案正好被切在边界上） */
    private int chunkOverlap = 30;

    /** 阿里云 OSS 参数（唯一存储出口；未配置时由 missingRequired() 报出缺失项） */
    private Oss oss = new Oss();

    /**
     * 阿里云 OSS 参数。
     *
     * 这里**不给假默认值**：默认值写在 application.yaml 的 ${OSS_XXX:兜底} 里。
     * 原因是 "your-access-key-id" 这类占位值不是默认值、只是"让应用能起来的假值"，
     * 它们还容易被误当成真的配好了。没配时为空串/ null，由 missingRequired() 明确报出来。
     */
    @Data
    public static class Oss {

        /** 地域节点，如 oss-cn-hangzhou.aliyuncs.com */
        private String endpoint;

        /** AccessKeyId（建议走环境变量，不要明文提交） */
        private String accessKeyId;

        /** AccessKeySecret（建议走环境变量，不要明文提交） */
        private String accessKeySecret;

        /** Bucket 名称。必须保持私有读，不要设成公共读 */
        private String bucketName;

        /** 对象 key 前缀，形如 rag-docs/123/1/<docId>/xxx.pdf；留空则不区分命名空间 */
        private String keyPrefix;

        /**
         * 返回"未配置的必填项"名字（空列表 = 配置完整）。
         * 存储层构造与启动告警共用这一份判定，避免两处各写一遍必填清单。
         */
        public List<String> missingRequired() {
            List<String> missing = new ArrayList<>();
            if (!StringUtils.hasText(endpoint)) {
                missing.add("endpoint");
            }
            if (!StringUtils.hasText(accessKeyId)) {
                missing.add("access-key-id");
            }
            if (!StringUtils.hasText(accessKeySecret)) {
                missing.add("access-key-secret");
            }
            if (!StringUtils.hasText(bucketName)) {
                missing.add("bucket-name");
            }
            return missing;
        }
    }

    /**
     * 配置自愈 + 打出生效值。
     * 字段有默认值，键名写错时 Spring 会静默忽略，外部看不出异常，
     * 所以把生效值打出来，和 yaml 对照即可发现。同时把明显非法的值拉回默认值。
     */
    @PostConstruct
    void normalize() {
        if (maxFileSize == null || maxFileSize.toBytes() <= 0) {
            maxFileSize = DataSize.ofMegabytes(20);
        }
        if (maxDocumentsPerUser <= 0) {
            maxDocumentsPerUser = 200;
        }
        if (chunkSize <= 0) {
            chunkSize = 300;
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            chunkOverlap = Math.min(30, chunkSize / 10);
        }
        if (allowedExtensions == null || allowedExtensions.isEmpty()) {
            allowedExtensions = List.of("pdf", "docx", "doc", "txt", "md", "markdown", "html", "htm", "xlsx", "xls", "pptx", "ppt", "csv");
        }
        if (oss == null) {
            oss = new Oss();
        }

        log.info("[llm.rag.upload] 生效配置: maxFileSize={} allowedExtensions={} "
                        + "maxDocumentsPerUser={} chunkSize={} chunkOverlap={} "
                        + "oss[endpoint={} bucket={} keyPrefix={} accessKeyId={}]",
                maxFileSize.toMegabytes() + "MB", allowedExtensions,
                maxDocumentsPerUser, chunkSize, chunkOverlap,
                oss.getEndpoint(), oss.getBucketName(), oss.getKeyPrefix(), mask(oss.getAccessKeyId()));

        // 客户上传只有 OSS 一条路，缺配置意味着上传功能直接不可用，启动时明确告警
        List<String> missing = oss.missingRequired();
        if (!missing.isEmpty()) {
            log.warn("[llm.rag.upload] OSS 未配置：{}。客户上传文档会失败"
                            + "（聊天与内置知识库不受影响）。请在 application.yaml 或环境变量里"
                            + "提供 llm.rag.upload.oss.*", missing);
        }
    }

    /** 密钥脱敏后再打日志（只留头尾各 4 位） */
    private static String mask(String secret) {
        if (secret == null || secret.length() <= 8) {
            return "***";
        }
        return secret.substring(0, 4) + "***" + secret.substring(secret.length() - 4);
    }
}
