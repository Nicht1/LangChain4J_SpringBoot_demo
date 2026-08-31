package com.llm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

/**
 * CORS 跨域配置。
 * <p>
 * 流式 SSE 端点（/api/stream/**）需要浏览器建立长连接读取事件流，
 * 必须放开跨域限制，否则前端 fetch/EventSource 会被浏览器拦截。
 */
@Configuration
public class CorsConfig {

    /**
     * 注册 CORS 过滤器，仅对 /api/stream/** 路径生效。
     * <p>
     * 注意：生产环境应把 allowedOrigins 改为具体域名，不要用 *。
     * exposedHeaders 中显式暴露 Content-Type 和 Content-Length，
     * 确保 SSE 流的前端能正确读取响应头。
     */
    @Bean
    public CorsFilter corsWebFilter() {
        CorsConfiguration corsConfig = new CorsConfiguration();
        corsConfig.setAllowedOrigins(Arrays.asList("*"));
        corsConfig.setMaxAge(3600L);
        corsConfig.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        corsConfig.setAllowedHeaders(Arrays.asList("*"));
        corsConfig.setExposedHeaders(Arrays.asList("Content-Type", "Content-Length"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/stream/**", corsConfig);

        return new CorsFilter(source);
    }
}
