package com.llm.mini.vo.request;

import lombok.Data;

@Data
public class MessageRequestVO {

    /** 会话 ID（前端会话标识；服务端会按 用户+智能体 隔离历史） */
    private String sessionId;

    /** 用户 ID（多租户隔离维度） */
    private Long userId = 123L;

    /** 智能体 ID（前端创建的动态智能体，决定 SYSTEM_MESSAGE 与 RAG 过滤） */
    private Long agentId = 1L;

    /** 用户消息 */
    private String message;
}
