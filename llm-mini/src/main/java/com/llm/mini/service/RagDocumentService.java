package com.llm.mini.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.llm.mini.config.RagUploadProperties;
import com.llm.mini.mapper.RagDocumentMapper;
import com.llm.mini.pojo.RagDocument;
import com.llm.mini.rag.DocumentStatus;
import com.llm.mini.rag.DocumentVisibility;
import com.llm.mini.rag.RagMetadataKeys;
import com.llm.mini.storage.DocumentStorage;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * 客户上传文档 → 写入向量库（多租户 RAG 知识库）。
 *
 * 与官方内置知识库的分工：
 *   内置文档 = resources/documentation 目录为真相来源，启动扫描对账（含自愈）
 *   客户上传 = rag_document 表为真相来源，上传即摄入，原文件存 OSS
 *
 * 客户上传只能进 OSS，不落应用本地磁盘——所以启动扫描天然看不到它们
 * （扫描路径是 langchain4j.milvus.document-path，与 OSS 无关），
 * 客户文档的增量/变更/删除全部由本类的接口驱动，与启动流程解耦。
 *
 * 为什么不复用启动扫描：那条路每篇文档都要做一次向量存在性探测
 * （1 次 embedding + 1 次检索），上传量一大，每次启动的成本会线性膨胀。
 *
 * 写入向量的元数据（决定检索时怎么过滤，key 见 RagMetadataKeys）：
 *   userId / agentId / visibility / doc_source / file_name
 *
 * 安全边界：ownerId 由调用方传入并原样信任。本项目当前没有登录态，
 * 所以只做"归属一致性校验"（删除/重试必须是自己上传的），挡不住"冒充他人 ownerId 上传"。
 * 接登录态后必须改为从服务端身份上下文取。
 */
@Service
public class RagDocumentService {

    private static final Logger log = LoggerFactory.getLogger(RagDocumentService.class);

    /** 文件名净化后允许的最大长度（各类文件系统/对象存储对单段名都有长度限制） */
    private static final int MAX_FILE_NAME_LENGTH = 80;

    private final RagDocumentMapper mapper;
    private final DocumentStorage storage;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final RagUploadProperties props;
    private final ExecutorService ingestExecutor;
    private final DocumentSplitter splitter;

    public RagDocumentService(RagDocumentMapper mapper,
                              DocumentStorage storage,
                              EmbeddingStore<TextSegment> embeddingStore,
                              EmbeddingModel embeddingModel,
                              RagUploadProperties props,
                              @Qualifier("ragIngestExecutor") ExecutorService ingestExecutor) {
        this.mapper = mapper;
        this.storage = storage;
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.props = props;
        this.ingestExecutor = ingestExecutor;
        // 分片器在构造期一次性建好：它无状态，每次上传重建没有意义
        this.splitter = DocumentSplitters.recursive(props.getChunkSize(), props.getChunkOverlap());
    }

    // ==================== 上传 ====================

    /** 上传结果：文档 + 是否命中去重（前端可据此提示"该文件已存在"） */
    public record UploadResult(RagDocument document, boolean duplicated) {
    }

    /**
     * 接收入口：校验 → 去重 → 落存储 + 落表 → 异步摄入。
     * 返回时状态是 PENDING，前端轮询 get() 看进度。
     */
    public UploadResult upload(MultipartFile file, Long ownerId, Long agentId, String visibilityRaw) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件为空");
        }
        if (ownerId == null || agentId == null) {
            throw new IllegalArgumentException("ownerId / agentId 不能为空");
        }

        String rawName = file.getOriginalFilename();
        String safeName = sanitizeFileName(rawName);
        String ext = extensionOf(safeName);
        if (!props.getAllowedExtensions().contains(ext)) {
            throw new IllegalArgumentException("不支持的文件类型: " + (ext.isEmpty() ? "(无扩展名)" : ext)
                    + "，允许: " + props.getAllowedExtensions());
        }
        if (file.getSize() > props.getMaxFileSize().toBytes()) {
            throw new IllegalArgumentException("文件超过大小上限 "
                    + props.getMaxFileSize().toMegabytes() + "MB，实际 "
                    + (file.getSize() / 1024 / 1024) + "MB");
        }

        // 配额：防止单个用户把磁盘/向量库吃满
        Long owned = mapper.selectCount(new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getOwnerId, ownerId));
        if (owned != null && owned >= props.getMaxDocumentsPerUser()) {
            throw new IllegalStateException("该用户的文档数已达上限 " + props.getMaxDocumentsPerUser()
                    + "，请先删除部分文档");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalStateException("读取上传内容失败: " + e.getMessage(), e);
        }
        // 用"文件字节"做指纹（不是解析后的文本）：目的是识别"同一个文件被重复上传"。
        // 注意与内置知识库不同——那边哈希的是解析后的文本，用于"文档内容是否变更"。
        String hash = sha256Hex(bytes);

        // 去重：同一 owner 下相同内容且已成功摄入的，直接复用，不再占用存储与向量
        RagDocument existing = mapper.selectOne(new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getOwnerId, ownerId)
                .eq(RagDocument::getContentHash, hash)
                .eq(RagDocument::getStatus, DocumentStatus.READY.name())
                .last("limit 1"));
        if (existing != null) {
            log.info("上传去重命中: owner={} hash={} 复用 docId={}", ownerId, hash.substring(0, 12), existing.getId());
            return new UploadResult(existing, true);
        }

        DocumentVisibility visibility = DocumentVisibility.parse(visibilityRaw);
        String docId = UUID.randomUUID().toString();
        String storageKey = ownerId + "/" + agentId + "/" + docId + "/" + safeName;
        String docSource = docSourceOf(ownerId, agentId, docId);

        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            storage.save(storageKey, in, bytes.length);
        } catch (Exception e) {
            throw new IllegalStateException("保存原文件失败: " + e.getMessage(), e);
        }

        LocalDateTime now = LocalDateTime.now();
        RagDocument doc = new RagDocument()
                .setId(docId)
                .setOwnerId(ownerId)
                .setAgentId(agentId)
                .setVisibility(visibility.name())
                .setFileName(safeName)
                .setFileExt(ext)
                .setContentType(file.getContentType())
                .setFileSize((long) bytes.length)
                .setContentHash(hash)
                .setStorageKey(storageKey)
                .setDocSource(docSource)
                .setStatus(DocumentStatus.PENDING.name())
                .setChunkCount(0)
                .setCreatedAt(now)
                .setUpdatedAt(now);
        mapper.insert(doc);
        log.info("📥 收到上传: docId={} owner={} agent={} visibility={} name={} size={}KB storage={}",
                docId, ownerId, agentId, visibility, safeName, bytes.length / 1024, storage.type());

        submitIngest(docId);
        return new UploadResult(doc, false);
    }

    // ==================== 查询 / 删除 / 重试 ====================

    /** 查单篇（归属校验）。前端轮询摄入进度用；越权返回 null，不区分"不存在"与"无权限" */
    public RagDocument get(String docId, Long ownerId) {
        RagDocument doc = mapper.selectById(docId);
        if (doc == null || !doc.getOwnerId().equals(ownerId)) {
            return null;
        }
        return doc;
    }

    /**
     * 列出当前智能体能看到的文档：本智能体的（AGENT）+ 本用户共享的（USER）。
     * 规则与向量检索保持一致，否则会出现"列表里看到但检索不到"。
     */
    public List<RagDocument> list(Long ownerId, Long agentId) {
        LambdaQueryWrapper<RagDocument> q = new LambdaQueryWrapper<RagDocument>()
                .eq(RagDocument::getOwnerId, ownerId);
        if (agentId != null) {
            q.and(w -> w.eq(RagDocument::getAgentId, agentId)
                    .or().eq(RagDocument::getVisibility, DocumentVisibility.USER.name()));
        }
        q.orderByDesc(RagDocument::getCreatedAt);
        return mapper.selectList(q);
    }

    /**
     * 删除文档：向量 → 表记录 → 原文件。
     * 顺序是刻意的，按"危害从大到小"删：幽灵向量会被检索到、污染回答（最有害）；
     * 幽灵文件只占磁盘（最无害）。中途失败就停在"还剩无害残留"的状态。
     *
     * @return false 表示归属校验不通过
     */
    public boolean delete(String docId, Long ownerId) {
        RagDocument doc = mapper.selectById(docId);
        if (doc == null || !doc.getOwnerId().equals(ownerId)) {
            return false;
        }
        // 1) 删向量（按 doc_source 锚点，与存储路径无关）
        embeddingStore.removeAll(metadataKey(RagMetadataKeys.DOC_SOURCE).isEqualTo(doc.getDocSource()));
        // 2) 删表记录
        mapper.deleteById(docId);
        // 3) 删原文件（失败只告警：留个孤儿文件远好过留孤儿向量）
        try {
            storage.delete(doc.getStorageKey());
        } catch (Exception e) {
            log.warn("原文件删除失败（已删向量与记录，磁盘可能残留孤儿文件）: docId={} key={} -> {}",
                    docId, doc.getStorageKey(), e.getMessage());
        }
        log.info("🗑 已删除上传文档: docId={} owner={} name={}", docId, ownerId, doc.getFileName());
        return true;
    }

    /** 重试摄入，仅 FAILED 可用（READY 重摄入会产生重复向量；PENDING/INDEXING 在跑，会并发摄入） */
    public boolean retry(String docId, Long ownerId) {
        RagDocument doc = mapper.selectById(docId);
        if (doc == null || !doc.getOwnerId().equals(ownerId)) {
            return false;
        }
        DocumentStatus current = DocumentStatus.parse(doc.getStatus());
        if (current == null || !current.canRetry()) {
            return false;
        }
        updateStatus(docId, DocumentStatus.PENDING, null, null);
        log.info("🔁 重试摄入: docId={} name={}", docId, doc.getFileName());
        submitIngest(docId);
        return true;
    }

    // ==================== 摄入 ====================

    private void submitIngest(String docId) {
        ingestExecutor.execute(() -> ingest(docId));
    }

    /** 摄入流程，跑在摄入线程池里。异常不外抛，一律置 FAILED 并记原因，让用户能看到失败并重试 */
    private void ingest(String docId) {
        RagDocument doc = mapper.selectById(docId);
        if (doc == null) {
            log.warn("摄入任务找不到文档记录，跳过: docId={}", docId);
            return;
        }
        long start = System.currentTimeMillis();
        updateStatus(docId, DocumentStatus.INDEXING, null, null);
        try (InputStream in = storage.open(doc.getStorageKey())) {
            // 1) 从"流"解析，不需要本地临时文件（存储是本地还是 OSS 都一样）。
            //    用无参构造、每次新建：2026-10-10 实测，裸字节流不给文件名提示也能靠 magic bytes
            //    正确识别 PDF/DOCX（连 zip 容器的 docx 都没误判）；带 Metadata 的构造器已过时，
            //    且共享 Metadata 实例会在多线程摄入时互相污染。
            Document document = new ApacheTikaDocumentParser().parse(in);

            // 2) 打隔离与溯源标签（检索靠这些字段过滤）
            document.metadata().put(RagMetadataKeys.USER_ID, String.valueOf(doc.getOwnerId()));
            document.metadata().put(RagMetadataKeys.AGENT_ID, String.valueOf(doc.getAgentId()));
            document.metadata().put(RagMetadataKeys.VISIBILITY, doc.getVisibility());
            document.metadata().put(RagMetadataKeys.DOC_SOURCE, doc.getDocSource());
            document.metadata().put(RagMetadataKeys.FILE_NAME, doc.getFileName());

            // 3) 分片
            List<TextSegment> segments = splitter.split(document);
            if (segments.isEmpty()) {
                throw new IllegalStateException(
                        "解析后没有可用于检索的文本（可能是扫描件/纯图片 PDF，需要 OCR）");
            }

            // 4) 先按锚点清旧向量，保证重试不产生重复分片
            embeddingStore.removeAll(metadataKey(RagMetadataKeys.DOC_SOURCE).isEqualTo(doc.getDocSource()));
            List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
            embeddingStore.addAll(embeddings, segments);

            // 5) 并发防护：摄入期间这份文档可能已被删除。删除是"删向量→删记录→删文件"，
            //    若它发生在本次 addAll 之前，刚写的向量就成幽灵数据了。这里补校验并回滚。
            if (mapper.selectById(docId) == null) {
                embeddingStore.removeAll(metadataKey(RagMetadataKeys.DOC_SOURCE).isEqualTo(doc.getDocSource()));
                log.warn("摄入完成时文档已被删除，已回滚刚写入的向量: docId={} name={}", docId, doc.getFileName());
                return;
            }

            updateStatus(docId, DocumentStatus.READY, segments.size(), null);
            log.info("✅ 摄入完成: docId={} name={} 分片={} 耗时={}ms",
                    docId, doc.getFileName(), segments.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
            updateStatus(docId, DocumentStatus.FAILED, 0, truncate(reason, 500));
            log.error("❌ 摄入失败: docId={} name={} 耗时={}ms -> {}",
                    docId, doc.getFileName(), System.currentTimeMillis() - start, reason);
            // 提示常见原因，减少"只看到 FAILED 不知道怎么办"
            if (reason.contains("PDF") || reason.contains("Encrypted") || reason.contains("password")) {
                log.error("  提示: 该文件可能是加密或纯图片型 PDF，需要先解密或做 OCR");
            }
        }
    }

    // ==================== 工具 ====================

    /** 统一的状态更新入口（chunkCount / failReason 传 null 表示不关心该字段） */
    private void updateStatus(String docId, DocumentStatus status, Integer chunkCount, String failReason) {
        LambdaUpdateWrapper<RagDocument> u = new LambdaUpdateWrapper<RagDocument>()
                .eq(RagDocument::getId, docId)
                .set(RagDocument::getStatus, status.name())
                .set(RagDocument::getUpdatedAt, LocalDateTime.now());
        if (chunkCount != null) {
            u.set(RagDocument::getChunkCount, chunkCount);
        }
        if (status == DocumentStatus.READY) {
            u.set(RagDocument::getFailReason, null);   // 成功则清掉历史失败原因
        } else if (failReason != null) {
            u.set(RagDocument::getFailReason, failReason);
        }
        mapper.update(null, u);
    }

    /** 向量锚点：与存储路径解耦，所以换本地/OSS、挪目录都不影响已写入的向量 */
    public static String docSourceOf(Long ownerId, Long agentId, String docId) {
        return "rag://" + ownerId + "/" + agentId + "/" + docId;
    }

    /**
     * 文件名净化，防路径穿越的第一道（存储层还有第二道）。
     * 只取最后一段（../../etc/passwd → passwd）、去掉路径分隔符与控制字符、
     * 其余非法字符替换为 _、限制长度、不允许结果是 . 或 ..
     */
    static String sanitizeFileName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "unnamed";
        }
        String name = rawName.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\x00-\\x1f\\x7f]", "")
                .replaceAll("[/\\\\:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        if (name.length() > MAX_FILE_NAME_LENGTH) {
            // 超长时保留扩展名，截断主体
            String ext = extensionOf(name);
            int keep = MAX_FILE_NAME_LENGTH - (ext.isEmpty() ? 0 : ext.length() + 1);
            name = name.substring(0, Math.max(1, keep)) + (ext.isEmpty() ? "" : "." + ext);
        }
        if (name.isBlank() || ".".equals(name) || "..".equals(name)) {
            return "unnamed";
        }
        return name;
    }

    /** 取扩展名（小写、不含点）；无扩展名返回空串 */
    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    /** SHA-256 十六进制 */
    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
