package com.llm.mini.tool;

/**
 * 工具标记接口（工具迁移）。
 * <p>
 * Spring 自动收集所有实现了该接口的 {@code @Component} Bean，
 * 注入到智能体工厂的 tools 列表中。新增工具只需添加 {@code @Component} 即可自动生效。
 */
public interface LlmTool {

}
