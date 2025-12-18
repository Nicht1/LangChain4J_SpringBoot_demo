package com.llm.pojo;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.Date;

/**
 * 适配 LangChain4j 的聊天消息实体
 */
@Data
@Accessors(chain = true)
@TableName("chat_message_entity")
public class ChatMessageEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_id")
    private String sessionId;

    @TableField("message_type")
    private String messageType; // SYSTEM, USER, AI_ASSISTANT

    @TableField("content")
    private String content;

    @TableField("tokens")
    private Integer tokens;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private Date createdTime;

    private String messageId;

}