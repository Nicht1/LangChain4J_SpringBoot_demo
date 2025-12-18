package com.llm.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class CommonTool implements LlmTool{

    @Tool("计算两个整数的和")
    public String add(int a, int b) {
        return String.valueOf(a + b);
    }

    @Tool("查看当前城市天气")
    public String getWeather(@P("城市名称，例如：北京、上海、广州、深圳")String city) {
        return String.valueOf("晴天");
    }
}
