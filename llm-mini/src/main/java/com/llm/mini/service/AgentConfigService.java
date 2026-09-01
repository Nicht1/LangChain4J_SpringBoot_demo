package com.llm.mini.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.llm.mini.mapper.AgentConfigMapper;
import com.llm.mini.pojo.AgentConfig;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 动态智能体配置服务（持久化 + 多租户归属隔离）。
 * <p>
 * 所有读取/修改方法都强制按 {@code id + ownerId} 校验，
 * 用户 A 无法读取或操作用户 B 的智能体（越权返回 null / 抛异常）。
 * 数据落在 MySQL llm_agent 表，重启不丢失。
 */
@Service
public class AgentConfigService {

    private final AgentConfigMapper agentConfigMapper;

    public AgentConfigService(AgentConfigMapper agentConfigMapper) {
        this.agentConfigMapper = agentConfigMapper;
    }

    /** 创建智能体（ownerId 由调用方/前端传入，落库后作为归属标识） */
    public AgentConfig create(AgentConfig agent) {
        agentConfigMapper.insert(agent);
        return agent;
    }

    /** 更新智能体（先按 id+ownerId 校验归属，防止越权修改他人 agent） */
    public AgentConfig update(AgentConfig agent) {
        if (getByIdAndOwner(agent.getId(), agent.getOwnerId()) == null) {
            throw new IllegalArgumentException("智能体不存在或无权限修改");
        }
        agentConfigMapper.updateById(agent);
        return agent;
    }

    /** 按 id + 归属查询（多租户：越权查询返回 null） */
    public AgentConfig getByIdAndOwner(Long id, Long ownerId) {
        return agentConfigMapper.selectOne(new LambdaQueryWrapper<AgentConfig>()
                .eq(AgentConfig::getId, id)
                .eq(AgentConfig::getOwnerId, ownerId));
    }

    /** 查询某用户创建的全部智能体 */
    public List<AgentConfig> listByOwner(Long ownerId) {
        return agentConfigMapper.selectList(new LambdaQueryWrapper<AgentConfig>()
                .eq(AgentConfig::getOwnerId, ownerId)
                .orderByDesc(AgentConfig::getId));
    }

    /** 逻辑删除智能体（先校验归属） */
    public boolean delete(Long id, Long ownerId) {
        if (getByIdAndOwner(id, ownerId) == null) {
            return false;
        }
        return agentConfigMapper.deleteById(id) > 0;
    }
}
