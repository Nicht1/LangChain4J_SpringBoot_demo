package com.llm.tool;

import com.llm.tool.LlmTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Date;

@Component
public class TimeTool implements LlmTool {

    @Tool("获取当前城市的时间")
    public String getTimeByCity(@P("城市名称，例如：北京、上海、广州、深圳")String city) {
        // 实际应该根据城市返回对应时间，这里简化处理
        return city + " 当前时间: " + new Date();
    }
}