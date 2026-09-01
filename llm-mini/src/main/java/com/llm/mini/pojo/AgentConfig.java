package com.llm.mini.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Date;

/**
 * 动态智能体配置实体（持久化到 llm_agent 表）。
 * <p>
 * 多用户场景：每个智能体有归属用户 {@link #ownerId}，
 * 所有操作（查询/更新/删除/聊天加载）都按 {@code id + ownerId} 校验，
 * 保证用户只能读取/使用自己创建的智能体。
 * <p>
 * 聊天时 {@code systemMessageProvider} 按 {@code ownerId + id} 查出该智能体的
 * {@link #systemMessage} 动态注入 LLM。
 */
@Data
@Accessors(chain = true)
@TableName("llm_agent")
public class AgentConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 归属用户 ID（多租户隔离：每个用户只能操作自己创建的智能体） */
    @TableField("owner_id")
    private Long ownerId;

    /** 智能体名称（前端展示用） */
    @TableField("agent_name")
    private String agentName;

    /** 系统提示词（人设），每次聊天时动态注入给 LLM */
    @TableField("system_message")
    private String systemMessage;

    /** 描述 */
    private String description;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createdTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date updatedTime;

    @TableField("deleted")
    @TableLogic
    private Integer deleted;
}
