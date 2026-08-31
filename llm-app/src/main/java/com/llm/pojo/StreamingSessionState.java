package com.llm.pojo;

import lombok.Data;

@Data
public class StreamingSessionState {

    private String sessionId;

    private Long userId;

    private String userMessage;

    private StringBuilder responseBuilder;

}
