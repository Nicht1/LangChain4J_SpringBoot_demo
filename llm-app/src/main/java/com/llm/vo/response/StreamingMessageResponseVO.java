package com.llm.vo.response;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StreamingMessageResponseVO {

    private String token;           // 当前 token

    private String sessionId;       // 会话 ID

    private Long userId;          // 用户 ID

    private boolean complete;       // 是否完成

    private boolean error;          // 是否错误

    private String errorMessage;    // 错误信息

    private String fullResponse;    // 完整响应（仅在完成时包含）

}
