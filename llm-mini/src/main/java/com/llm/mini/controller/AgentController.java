package com.llm.mini.controller;

import com.llm.mini.pojo.AgentConfig;
import com.llm.mini.service.AgentConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 动态智能体管理接口 —— 供前端"创建不同 SYSTEM_MESSAGE 的智能体"。
 * <p>
 * 多租户归属隔离：所有接口都带 {@code userId}，服务层按 {@code id + ownerId}
 * 校验，用户只能管理自己创建的智能体（越权返回失败 / null）。
 * <p>
 * API 路径：/api/agent
 * <ul>
 *   <li>POST /create  — 创建智能体 { ownerId, agentName, systemMessage, description }</li>
 *   <li>POST /update  — 更新智能体（归属校验）</li>
 *   <li>GET  /list    — 列出某用户的智能体 ?userId=</li>
 *   <li>GET  /get     — 按 id 查询（归属校验）?id=&userId=</li>
 *   <li>POST /delete  — 删除智能体（归属校验）?id=&userId=</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentConfigService agentConfigService;

    public AgentController(AgentConfigService agentConfigService) {
        this.agentConfigService = agentConfigService;
    }

    /** 创建智能体（ownerId 由前端传入；接入登录态后应从 token 取） */
    @PostMapping("/create")
    public Map<String, Object> create(@RequestBody AgentConfig agentConfig) {
        Map<String, Object> result = new HashMap<>();
        try {
            agentConfigService.create(agentConfig);
            result.put("success", true);
            result.put("data", agentConfig);
            result.put("message", "智能体创建成功");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "创建失败: " + e.getMessage());
        }
        return result;
    }

    /** 更新智能体（内部做归属校验） */
    @PostMapping("/update")
    public Map<String, Object> update(@RequestBody AgentConfig agentConfig) {
        Map<String, Object> result = new HashMap<>();
        try {
            agentConfigService.update(agentConfig);
            result.put("success", true);
            result.put("data", agentConfig);
            result.put("message", "智能体更新成功");
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "更新失败: " + e.getMessage());
        }
        return result;
    }

    /** 列出某用户创建的全部智能体 */
    @GetMapping("/list")
    public Map<String, Object> list(@RequestParam Long userId) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("data", agentConfigService.listByOwner(userId));
        return result;
    }

    /** 按 id 查询（归属校验，越权返回 success=false） */
    @GetMapping("/get")
    public Map<String, Object> get(@RequestParam Long id, @RequestParam Long userId) {
        Map<String, Object> result = new HashMap<>();
        AgentConfig agent = agentConfigService.getByIdAndOwner(id, userId);
        result.put("success", agent != null);
        result.put("data", agent);
        result.put("message", agent != null ? "查询成功" : "智能体不存在或无权限");
        return result;
    }

    /** 删除智能体（归属校验） */
    @PostMapping("/delete")
    public Map<String, Object> delete(@RequestParam Long id, @RequestParam Long userId) {
        Map<String, Object> result = new HashMap<>();
        boolean ok = agentConfigService.delete(id, userId);
        result.put("success", ok);
        result.put("message", ok ? "删除成功" : "智能体不存在或无权限");
        return result;
    }
}
