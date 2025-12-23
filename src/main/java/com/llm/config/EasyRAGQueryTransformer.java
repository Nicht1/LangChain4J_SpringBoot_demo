package com.llm.config;

import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import org.springframework.stereotype.Component;

import java.util.List;

// 使用用户 Query 加强输入、可以让用户携带携带相关内容 userId, sessionId 等
//@Component
public class EasyRAGQueryTransformer implements QueryTransformer {

    @Override
    public List<Query> transform(Query query) {

        String userId = (String) query.metadata()
                .invocationParameters()
                .asMap()
                .get("userId");

        // 给 query 加上明确上下文
        String rewritten = "User " + userId + " asks: " + query.text();

        return List.of(
                Query.from(rewritten, query.metadata())
        );
    }
}
