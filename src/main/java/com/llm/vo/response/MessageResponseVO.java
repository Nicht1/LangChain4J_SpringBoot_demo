package com.llm.vo.response;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class MessageResponseVO {

    private String sessionId;

    private Long userId;

    private String response;

}
