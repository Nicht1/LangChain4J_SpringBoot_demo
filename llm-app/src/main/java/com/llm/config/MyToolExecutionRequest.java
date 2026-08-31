package com.llm.config;

import dev.langchain4j.service.tool.DefaultToolExecutor;

import java.lang.reflect.Method;

public class MyToolExecutionRequest extends DefaultToolExecutor {

    public MyToolExecutionRequest(Object object, Method method) {
        super(object, method);
    }


}
