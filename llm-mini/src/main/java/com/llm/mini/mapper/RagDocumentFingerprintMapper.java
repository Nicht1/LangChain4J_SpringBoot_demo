package com.llm.mini.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.llm.mini.pojo.RagDocumentFingerprint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RagDocumentFingerprintMapper extends BaseMapper<RagDocumentFingerprint> {

    /** 查询所有未标记删除的记录（现有文件） */
    @Select("SELECT * FROM rag_document_fingerprint")
    List<RagDocumentFingerprint> findAll();

    /** 按文件路径查询 */
    @Select("SELECT * FROM rag_document_fingerprint WHERE file_path = #{filePath}")
    RagDocumentFingerprint findByFilePath(@Param("filePath") String filePath);
}
