package com.llm.mini.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llm.mini.mapper.RagDocumentFingerprintMapper;
import com.llm.mini.pojo.RagDocumentFingerprint;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * RAG 文档指纹服务：管理文档内容哈希的持久化，用于增量同步判断
 */
@Service
public class RagDocumentFingerprintService {

    private final RagDocumentFingerprintMapper fingerprintMapper;

    public RagDocumentFingerprintService(RagDocumentFingerprintMapper fingerprintMapper) {
        this.fingerprintMapper = fingerprintMapper;
    }

    /**
     * 加载所有历史指纹
     * @return {文件路径: SHA-256 哈希}
     */
    public Map<String, String> loadAll() {
        List<RagDocumentFingerprint> all = fingerprintMapper.selectList(null);
        if (all.isEmpty()) {
            System.out.println("📌 首次运行，数据库中无指纹记录");
            return Collections.emptyMap();
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (RagDocumentFingerprint fp : all) {
            map.put(fp.getFilePath(), fp.getContentHash());
        }
        System.out.println("📂 已加载 " + all.size() + " 条历史指纹记录");
        return map;
    }

    /**
     * 保存或更新指纹
     */
    public void saveOrUpdate(String filePath, String contentHash) {
        RagDocumentFingerprint existing = fingerprintMapper.selectOne(
                new LambdaQueryWrapper<RagDocumentFingerprint>()
                        .eq(RagDocumentFingerprint::getFilePath, filePath)
        );
        if (existing != null) {
            existing.setContentHash(contentHash).setUpdatedAt(LocalDateTime.now());
            fingerprintMapper.updateById(existing);
        } else {
            fingerprintMapper.insert(
                    new RagDocumentFingerprint()
                            .setFilePath(filePath)
                            .setContentHash(contentHash)
                            .setUpdatedAt(LocalDateTime.now())
            );
        }
    }

    /**
     * 删除单条指纹
     */
    public void deleteByFilePath(String filePath) {
        fingerprintMapper.delete(
                new LambdaQueryWrapper<RagDocumentFingerprint>()
                        .eq(RagDocumentFingerprint::getFilePath, filePath)
        );
    }
}
