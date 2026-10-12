package com.llm.mini.controller;

import com.llm.mini.pojo.RagDocument;
import com.llm.mini.service.RagDocumentService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户上传文档 → 多租户 RAG 知识库。路径 /api/rag/document
 *
 * POST /upload  上传并异步摄入（multipart: file + ownerId + agentId + visibility）
 * GET  /list    列出该智能体可见的文档 ?ownerId=&agentId=
 * GET  /get     查单篇（轮询摄入进度）?docId=&ownerId=
 * POST /delete  删除（向量 + 记录 + 原文件）?docId=&ownerId=
 * POST /retry   重试摄入（仅 FAILED）?docId=&ownerId=
 *
 * visibility：AGENT=仅该智能体可见（默认），USER=该用户全部智能体可见，两者都不跨用户。
 *
 * 注意 ownerId 目前由前端传入并原样信任（与 AgentController 同款 ），
 * 接登录态后必须改为从服务端身份上下文取。
 */
@RestController
@RequestMapping("/api/rag/document")
public class RagDocumentController {

    private final RagDocumentService documentService;

    public RagDocumentController(RagDocumentService documentService) {
        this.documentService = documentService;
    }

    /** 上传文档并触发异步摄入。立即返回 PENDING + docId，摄入在后台跑，前端轮询 /get 看进度 */
    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file,
                                      @RequestParam("ownerId") Long ownerId,
                                      @RequestParam("agentId") Long agentId,
                                      @RequestParam(value = "visibility", defaultValue = "AGENT") String visibility) {
        Map<String, Object> result = new HashMap<>();
        try {
            RagDocumentService.UploadResult r = documentService.upload(file, ownerId, agentId, visibility);
            result.put("success", true);
            result.put("data", r.document());
            result.put("duplicated", r.duplicated());
            result.put("message", r.duplicated()
                    ? "该文件已存在（内容相同），直接复用已有文档，未重复占用存储与向量"
                    : "上传成功，正在后台解析并写入知识库，请轮询 /get 查看进度");
        } catch (IllegalArgumentException | IllegalStateException e) {
            // 参数/配额类错误：属于调用方可修正的问题，返回明确原因
            result.put("success", false);
            result.put("message", e.getMessage());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "上传失败: " + e.getMessage());
        }
        return result;
    }

    /** 列出该智能体可见的上传文档（本智能体的 AGENT 文档 + 本用户的 USER 共享文档） */
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam Long ownerId,
                                   @RequestParam(required = false) Long agentId) {
        Map<String, Object> result = new HashMap<>();
        try {
            List<RagDocument> docs = documentService.list(ownerId, agentId);
            result.put("success", true);
            result.put("data", docs);
            result.put("total", docs.size());
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "查询失败: " + e.getMessage());
        }
        return result;
    }

    /** 查询单篇（归属校验；前端轮询摄入进度用） */
    @GetMapping("/get")
    public Map<String, Object> get(@RequestParam String docId, @RequestParam Long ownerId) {
        Map<String, Object> result = new HashMap<>();
        RagDocument doc = documentService.get(docId, ownerId);
        result.put("success", doc != null);
        result.put("data", doc);
        result.put("message", doc != null ? "查询成功" : "文档不存在无权限");
        return result;
    }

    /** 删除文档（归属校验；同时清理向量、表记录、原文件） */
    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestParam String docId, @RequestParam Long ownerId) {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean ok = documentService.delete(docId, ownerId);
            result.put("success", ok);
            result.put("message", ok ? "删除成功" : "文档不存在或无权限");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "删除失败: " + e.getMessage());
        }
        return result;
    }

    /** 重试摄入（仅对 FAILED 的文档有效） */
    @PostMapping("/retry")
    public Map<String, Object> retry(@RequestParam String docId, @RequestParam Long ownerId) {
        Map<String, Object> result = new HashMap<>();
        try {
            boolean ok = documentService.retry(docId, ownerId);
            result.put("success", ok);
            result.put("message", ok ? "已重新提交摄入，请轮询 /get 查看进度"
                    : "重试失败：文档不存在、无权限，或状态不是 FAILED");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "重试失败: " + e.getMessage());
        }
        return result;
    }
}
