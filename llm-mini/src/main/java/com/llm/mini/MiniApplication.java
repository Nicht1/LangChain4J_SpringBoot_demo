package com.llm.mini;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * llm-mini 模块启动类。
 * <p>
 * 极简流式 Chat 模块，只保留一个流式输出出口（SSE /api/stream/chat）与一个智能体。
 * 独立端口运行（默认 8081），可与主项目（8080）同时启动，共享同一 MySQL / Milvus。
 */
@SpringBootApplication
public class MiniApplication {

    public static void main(String[] args) {
        SpringApplication.run(MiniApplication.class, args);
    }

}
