package com.llm.config;

import com.llm.service.RagDocumentFingerprintService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
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

@Configuration
public class EasyRAGEmbeddingStoreConfig {

    /** 元数据 key，用于标记向量所属的源文件 */
    private static final String META_DOC_SOURCE = "doc_source";

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

    @Value("${langchain4j.milvus.document-path:D:\\work\\LLM-Spring\\src\\main\\resources\\documentation}")
    private String documentPath;

    private final RagDocumentFingerprintService fingerprintService;

    public EasyRAGEmbeddingStoreConfig(RagDocumentFingerprintService fingerprintService) {
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
     *   - 新文件   → 写入向量 + 插入指纹
     *   - 已变更   → 删除旧向量 → 写入新向量 + 更新指纹
     *   - 无变化   → 跳过
     *   - 已删除   → 删除向量 + 删除指纹
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

        // 4. 处理新增 / 变更的文件
        int newCount = 0, changedCount = 0, skippedCount = 0;

        for (Map.Entry<String, Document> entry : currentDocsByPath.entrySet()) {
            String filePath = entry.getKey();
            Document document = entry.getValue();
            String currentHash = currentFingerprints.get(filePath);
            String storedHash = storedFingerprints.get(filePath);

            if (storedHash == null) {
                newCount++;
                System.out.println("🆕 新文档，写入向量: " + filePath);
            } else if (!storedHash.equals(currentHash)) {
                changedCount++;
                System.out.println("📝 文档已变更，更新向量: " + filePath);
                store.removeAll(metadataKey(META_DOC_SOURCE).isEqualTo(filePath));
            } else {
                skippedCount++;
                continue;
            }

            // 写入向量到 Milvus
            document.metadata().put(META_DOC_SOURCE, filePath);
            EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                    .embeddingStore(store)
                    .embeddingModel(embeddingModel)
                    .build();
            ingestor.ingest(document);

            // 保存指纹到 MySQL
            fingerprintService.saveOrUpdate(filePath, currentHash);
        }

        // 5. 输出同步摘要
        System.out.println("========================================");
        System.out.println("📋 文档同步完成:");
        System.out.println("   新增: " + newCount + " | 变更: " + changedCount
                + " | 删除: " + deletedCount + " | 跳过(无变化): " + skippedCount);
        System.out.println("   集合: " + collectionName + " | 指纹存储: MySQL");
        System.out.println("========================================");
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
