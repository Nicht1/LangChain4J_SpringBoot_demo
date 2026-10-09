package com.llm.mini.config;

import com.llm.mini.service.RagDocumentFingerprintService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.*;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Milvus 向量存储 & 文档同步配置（向量迁移）。
 * <p>
 * 这是整个 RAG 系统的存储层基础，负责：
 * <ul>
 *   <li><b>EmbeddingModel Bean</b> — 本地 BGE-small-zh 模型（512 维），做文本向量化</li>
 *   <li><b>EmbeddingStore Bean</b> — Milvus 向量数据库客户端，存储文档向量</li>
 *   <li><b>增量同步</b> — 启动时扫描文档目录，基于 SHA-256 指纹增量更新 Milvus</li>
 * </ul>
 * <p>
 * 模型分工：
 * <pre>
 * 聊天  → DeepSeek（远程 API）
 * 向量化 → BGE-small-zh（本地 ONNX，无 API 成本）
 * 存储  → Milvus（远程向量库）
 * 指纹  → MySQL（rag_document_fingerprint 表）
 * </pre>
 */
@Configuration
public class EmbeddingStoreConfig {

    /** 写入 Milvus 时打在每条向量上的元数据 key，标记它来自哪个源文件 */
    private static final String META_DOC_SOURCE = "doc_source";

    /** 自愈探测时取文档开头多长做查询（够表达主题即可，不必太长） */
    private static final int PROBE_LENGTH = 200;

    @Value("${langchain4j.milvus.host}")
    private String host;

    @Value("${langchain4j.milvus.port}")
    private int port;

    @Value("${langchain4j.milvus.database-name}")
    private String databaseName;

    @Value("${langchain4j.milvus.collection-name}")
    private String collectionName;

    @Value("${langchain4j.milvus.dimension}")
    private int dimension;

    @Value("${langchain4j.milvus.reload-on-startup:false}")
    private boolean reloadOnStartup;

    @Value("${langchain4j.milvus.document-path:D:\\work\\LLM-Spring\\llm-mini\\src\\main\\resources\\documentation}")
    private String documentPath;

    /** 摄入文档时默认打的归属用户 ID 标签（RAG 检索按 userId 过滤） */
    @Value("${llm.rag.default-user-id:123}")
    private long defaultUserId;

    /** 摄入文档时默认打的归属智能体 ID 标签（RAG 检索按 agentId 过滤） */
    @Value("${llm.rag.default-agent-id:1}")
    private long defaultAgentId;

    private final RagDocumentFingerprintService fingerprintService;

    public EmbeddingStoreConfig(RagDocumentFingerprintService fingerprintService) {
        this.fingerprintService = fingerprintService;
    }

    /**
     * 本地中文 Embedding 模型（BGE-small-zh，512 维，无需 API）
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        BgeSmallZhEmbeddingModel model = new BgeSmallZhEmbeddingModel();
        System.out.println("🔧 初始化本地 Embedding 模型: BGE-small-zh (维度=" + model.dimension() + ")");
        return model;
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(EmbeddingModel embeddingModel) {
        MilvusEmbeddingStore store = MilvusEmbeddingStore.builder()
                .host(host)
                .port(port)
                .databaseName(databaseName)
                .collectionName(collectionName)
                .dimension(dimension)
                .build();

        if (reloadOnStartup) {
            syncDocuments(store, embeddingModel);
        }

        return store;
    }

    /**
     * 基于文件指纹的增量同步（指纹存储在 MySQL）：
     * <pre>
     *   新文件              → 写入向量 + 插入指纹
     *   已变更              → 删除旧向量 → 写入新向量 + 更新指纹
     *   指纹在但向量缺失    → 删除(残留)向量 → 重新写入 + 更新指纹   ← ★ 自愈
     *   无变化且向量在      → 跳过
     *   已删除              → 删除向量 + 删除指纹
     * </pre>
     *
     * <h2>为什么需要"向量缺失"这条自愈分支</h2>
     * 只比对指纹有个致命盲区：<b>指纹说"已摄入"，但向量其实不在</b>。
     * 典型触发场景是"为了改维度/换模型而删掉并重建集合"——指纹行还留着，
     * 于是每次启动都判定"无变化 → 跳过"，<b>知识库永远补不回来，而且不报任何错</b>。
     * 本项目 2026-10-08 就真实踩到过：集合被重建后实体数为 0，RAG 从此静默失效，
     * 最后只能手工删指纹才恢复。
     * <p>
     * 因此这里对"指纹匹配"的文档再做一次<b>存在性探测</b>：拿该文档自身开头做查询向量，
     * 按 {@code doc_source} 过滤检索一次——只要还能召回任何一条，就说明向量确实在。
     * 探测不到就视为缺失，删掉残留后重新摄入。
     *
     * <h2>副作用与安全边界</h2>
     * <ul>
     *   <li>探测失败（网络/集合异常）时<b>保守认为"向量在"</b>，宁可漏治也不误删重摄；</li>
     *   <li>每篇文档独立 try/catch：<b>一篇失败不影响其它篇</b>，且<b>失败就不写指纹</b>，
     *       这样下次启动会自动重试（指纹只有在摄入成功后才落库）。</li>
     * </ul>
     */
    private void syncDocuments(EmbeddingStore<TextSegment> store, EmbeddingModel embeddingModel) {
        // 1. 从 DB 加载历史指纹
        Map<String, String> storedFingerprints = fingerprintService.loadAll();

        // 2. 加载目录下所有文档，计算当前指纹
        List<Document> documents = FileSystemDocumentLoader.loadDocuments(documentPath);
        Map<String, Document> currentDocsByPath = indexByRelativePath(documents);
        Map<String, String> currentFingerprints = computeFingerprints(currentDocsByPath);

        if (documents.isEmpty() && storedFingerprints.isEmpty()) {
            System.out.println("⚠ 未在路径 [" + documentPath + "] 找到任何文档，且无历史数据");
            return;
        }

        // 3. 处理已删除的文件：从 Milvus 移除向量，从 DB 删除指纹
        int deletedCount = 0;
        for (String oldPath : storedFingerprints.keySet()) {
            if (!currentFingerprints.containsKey(oldPath)) {
                store.removeAll(metadataKey(META_DOC_SOURCE).isEqualTo(oldPath));
                fingerprintService.deleteByFilePath(oldPath);
                System.out.println("🗑 文件已删除，移除向量与指纹: " + oldPath);
                deletedCount++;
            }
        }

        // 4. 处理新增 / 变更 / 向量缺失自愈的文件
        int newCount = 0, changedCount = 0, skippedCount = 0, repairedCount = 0, failedCount = 0;

        for (Map.Entry<String, Document> entry : currentDocsByPath.entrySet()) {
            String filePath = entry.getKey();
            Document document = entry.getValue();
            String currentHash = currentFingerprints.get(filePath);
            String storedHash = storedFingerprints.get(filePath);

            boolean needRemoveOldVectors;
            if (storedHash == null) {
                newCount++;
                needRemoveOldVectors = false;
                System.out.println("🆕 新文档，写入向量: " + filePath);
            } else if (!storedHash.equals(currentHash)) {
                changedCount++;
                needRemoveOldVectors = true;
                System.out.println("📝 文档已变更，更新向量: " + filePath);
            } else if (!hasVectors(store, embeddingModel, document, filePath)) {
                // ★ 自愈：指纹说已摄入，但向量其实不在（例如集合被删掉重建过）
                repairedCount++;
                needRemoveOldVectors = true;
                System.out.println("🩹 指纹存在但向量缺失，重新摄入: " + filePath);
            } else {
                skippedCount++;
                continue;
            }

            try {
                if (needRemoveOldVectors) {
                    // 先清旧向量，避免重新摄入后同一个 doc_source 出现重复片段
                    store.removeAll(metadataKey(META_DOC_SOURCE).isEqualTo(filePath));
                }

                document.metadata().put(META_DOC_SOURCE, filePath);
                // 多租户 RAG 隔离标签：摄入时打上归属 userId / agentId，
                // 检索时 RagContentRetriever 按 userId/agentId 拼 AND 过滤，
                // 从而保证"对应 agent 只检索到自己的知识库"（后续可按文档上传来源覆盖这两个值）
                document.metadata().put("userId", String.valueOf(defaultUserId));
                document.metadata().put("agentId", String.valueOf(defaultAgentId));
                EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                        .embeddingStore(store)
                        .embeddingModel(embeddingModel)
                        .build();
                ingestor.ingest(document);

                // ★ 只有摄入成功才写指纹。失败就不写 → 下次启动会被当成"待摄入"自动重试，
                //   而不是留下一条"假成功"的指纹把文档永久锁死。
                fingerprintService.saveOrUpdate(filePath, currentHash);
            } catch (Exception e) {
                failedCount++;
                System.err.println("⚠ 摄入失败（不写指纹，下次启动自动重试）: " + filePath
                        + " -> " + e.getMessage());
            }
        }

        // 5. 输出同步摘要
        System.out.println("========================================");
        System.out.println("📋 文档同步完成:");
        System.out.println("   新增: " + newCount + " | 变更: " + changedCount
                + " | 自愈重摄: " + repairedCount + " | 删除: " + deletedCount
                + " | 跳过(无变化): " + skippedCount + " | 失败: " + failedCount);
        System.out.println("   集合: " + collectionName + " | 指纹存储: MySQL");
        System.out.println("========================================");
    }

    /**
     * 存在性探测：该 {@code doc_source} 在 Milvus 里是否还有向量。
     * <p>
     * 取文档开头一小段作为查询向量，按 {@code doc_source} 过滤、只取 1 条。
     * 用文档自己的内容去查它自己的片段，只要向量在，相似度必然是正数——所以
     * {@code minScore} 留 0 即可（这里只问"在不在"，不是问"像不像"）。
     * <p>
     * 任何异常都返回 {@code true}（保守认为存在）：宁可漏治，也不要因为一次探测抖动
     * 就把正常数据删掉重摄。
     */
    private boolean hasVectors(EmbeddingStore<TextSegment> store,
                               EmbeddingModel embeddingModel,
                               Document document,
                               String filePath) {
        try {
            String text = document.text();
            if (text == null || text.isBlank()) {
                return true;   // 空文档无从探测，交给正常流程处理
            }
            String probe = text.length() > PROBE_LENGTH ? text.substring(0, PROBE_LENGTH) : text;

            Embedding probeEmbedding = embeddingModel.embed(probe).content();
            EmbeddingSearchResult<TextSegment> result = store.search(EmbeddingSearchRequest.builder()
                    .queryEmbedding(probeEmbedding)
                    .maxResults(1)
                    .minScore(0.0)   // 只判断存在性，不设相似度门槛
                    .filter(metadataKey(META_DOC_SOURCE).isEqualTo(filePath))
                    .build());
            return !result.matches().isEmpty();
        } catch (Exception e) {
            System.out.println("⚠ 向量存在性探测失败，本次跳过自愈判断: " + filePath
                    + " -> " + e.getMessage());
            return true;
        }
    }

    // ==================== 文件哈希工具方法 ====================

    /** 计算每个文件的 SHA-256 指纹 */
    private Map<String, String> computeFingerprints(Map<String, Document> docsByPath) {
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (Map.Entry<String, Document> entry : docsByPath.entrySet()) {
            fingerprints.put(entry.getKey(), sha256(entry.getValue().text()));
        }
        return fingerprints;
    }

    /** SHA-256 哈希 */
    private String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 计算失败", e);
        }
    }

    /** 将文档列表按相对路径索引（相对于 documentPath） */
    private Map<String, Document> indexByRelativePath(List<Document> documents) {
        Path basePath = Paths.get(documentPath).toAbsolutePath().normalize();
        Map<String, Document> map = new LinkedHashMap<>();
        for (Document doc : documents) {
            String absPath = doc.metadata().getString("absolute_directory_path");
            String fileName = doc.metadata().getString("file_name");
            if (absPath == null || fileName == null) {
                continue;
            }
            Path docPath = Paths.get(absPath, fileName).toAbsolutePath().normalize();
            Path relativePath = basePath.relativize(docPath);
            map.put(relativePath.toString().replace('\\', '/'), doc);
        }
        return map;
    }
}
