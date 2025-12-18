package com.llm.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具调用请求序列化工具类
 */
public class ToolRequestSerializer {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private ToolRequestSerializer() {
        // 工具类，防止实例化
    }

    /**
     * 序列化工具调用请求为 JSON 字符串
     */
    public static String serializeToolExecutionRequests(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return null;
        }

        try {
            List<Map<String, Object>> requestData = new ArrayList<>();
            for (ToolExecutionRequest request : requests) {
                Map<String, Object> data = new HashMap<>();
                data.put("id", request.id());
                data.put("name", request.name());
                data.put("arguments", request.arguments());
                requestData.add(data);
            }
            return objectMapper.writeValueAsString(requestData);
        } catch (Exception e) {
            System.err.println("❌ 序列化工具调用请求失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从 JSON 字符串解析工具调用请求
     */
    public static List<ToolExecutionRequest> parseToolExecutionRequests(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }

        try {
            List<Map<String, Object>> requestData = objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
            List<ToolExecutionRequest> requests = new ArrayList<>();

            for (Map<String, Object> data : requestData) {
                String id = (String) data.get("id");
                String name = (String) data.get("name");
                String arguments = (String) data.get("arguments");

                ToolExecutionRequest request = ToolExecutionRequest.builder()
                        .id(id)
                        .name(name)
                        .arguments(arguments)
                        .build();

                requests.add(request);
            }
            return requests;
        } catch (Exception e) {
            System.err.println("❌ 解析工具调用请求失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 生成消息ID
     */
    public static String generateMessageId(String messageType) {
        String prefix = switch (messageType) {
            case "AI" -> "ai";
            case "USER" -> "user";
            case "SYSTEM" -> "sys";
            case "TOOL_EXECUTION_RESULT" -> "tool";
            default -> "msg";
        };
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 估算文本的token数量
     */
    public static int estimateTokens(String text) {
        if (text == null) return 0;
        // 简单估算：英文约 1 token = 4 字符，中文约 1 token = 2 字符
        return text.length() / 3;
    }

    /**
     * 从工具执行结果内容推断工具名称
     */
    public static String inferToolNameFromContent(String content) {
        if (content == null) {
            return "unknown_tool";
        }

        if (content.contains("时间") || content.contains("Time") || content.contains("几点")) {
            return "getTime";
        } else if (content.contains("计算") || content.contains("加") || content.contains("+") || content.contains("等于")) {
            return "add";
        } else if (content.contains("天气") || content.contains("Weather")) {
            return "getWeather";
        } else if (content.contains("查询") || content.contains("搜索")) {
            return "search";
        }
        return "unknown_tool";
    }

    /**
     * 验证工具调用请求的完整性
     */
    public static boolean validateToolRequests(List<ToolExecutionRequest> requests) {
        if (requests == null) {
            return true; // 空请求是有效的
        }

        for (ToolExecutionRequest request : requests) {
            if (request.id() == null || request.id().trim().isEmpty()) {
                return false;
            }
            if (request.name() == null || request.name().trim().isEmpty()) {
                return false;
            }
            // arguments 可以为空，有些工具不需要参数
        }
        return true;
    }

    /**
     * 将工具调用请求转换为可读的字符串（用于日志）
     */
    public static String toReadableString(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return "[]";
        }

        return requests.stream()
                .map(req -> String.format("%s(%s)", req.name(), req.arguments()))
                .collect(Collectors.joining(", ", "[", "]"));
    }
}