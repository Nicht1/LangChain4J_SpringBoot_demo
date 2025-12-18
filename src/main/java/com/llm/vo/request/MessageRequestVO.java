package com.llm.vo.request;

import lombok.Data;

@Data
public class MessageRequestVO {

    private String sessionId;

    private Long userId = 123L;

    private String message;
}
