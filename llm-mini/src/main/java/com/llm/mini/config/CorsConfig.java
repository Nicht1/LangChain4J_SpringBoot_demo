package com.llm.mini.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

/**
 * CORS 跨域配置。
 * <p>
 * 需要跨域的两类端点：
 * <ul>
 *   <li>{@code /api/stream/**} —— 流式 SSE 端点，浏览器要建立长连接读取事件流，
 *       不放开会直接被拦。</li>
 *   <li>{@code /api/agent/**} —— 智能体 CRUD。原来只放开了 {@code /api/stream/**}，
 *       结果从别的来源（或直接双击打开 html 文件）访问时，智能体列表/保存请求
 *       会被浏览器拦掉；而前端若没有 catch，表现就是"点了没反应"，极难排查。</li>
 * </ul>
 * 因此统一放开 {@code /api/**}。
 * <p>
 * 默认同源访问（页面由本应用自己托管）时 CORS 根本不参与，所以更推荐直接用
 * {@code http://<host>:8081/} 打开页面；这里放开只是为了排查方便。
 * <p>
 * <b>生产环境务必把 allowedOrigins 改成具体域名，不要用 {@code *}</b>，
 * 并配合鉴权（当前 userId / ownerId 由前端传入，无鉴权，可越权）。
 */
@Configuration
public class CorsConfig {

    /**
     * 注册 CORS 过滤器，对 {@code /api/**} 生效。
     * <p>
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
        source.registerCorsConfiguration("/api/**", corsConfig);

        return new CorsFilter(source);
    }
}
