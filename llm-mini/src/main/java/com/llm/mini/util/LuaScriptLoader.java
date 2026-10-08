package com.llm.mini.util;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 classpath 加载 Lua 脚本为 {@link RedisScript}。
 *
 * <h2>为什么把脚本放到 .lua 文件里，而不是写在 Java 字符串中</h2>
 * <ul>
 *   <li><b>可独立验证</b>：.lua 文件能直接丢给真 Redis 跑
 *       （{@code redis-cli --eval chat_memory_write.lua <key> , <args...>}），
 *       不必先启动整个 Spring 应用、也不必先跑通 Java 调用链。
 *       脚本里最容易出错的正是 LTRIM 的负索引、TTL 分支、空窗口分支这类边界。</li>
 *   <li><b>不占用 Java 缩进</b>：多行文本块写在方法/字段旁边会破坏代码层次，
 *       而 Lua 自身的缩进语义又会被 Java 的排版习惯带着走。</li>
 *   <li><b>可读性与高亮</b>：编辑器能对 .lua 正常高亮、检查语法。</li>
 *   <li><b>契约就近</b>：KEYS/ARGV 的约定直接写在脚本头部，改脚本的人自然会看到；
 *       放 Java 注释里则容易在只改 .lua 时被遗漏。</li>
 * </ul>
 *
 * <h2>读取要点</h2>
 * <ul>
 *   <li>显式按 <b>UTF-8</b> 读取。脚本里可能有中文注释，依赖平台默认编码会在
 *       换机器/换构建环境时静默乱码（进而可能破坏脚本内容）。</li>
 *   <li><b>读不到就抛异常</b>，不做"回退到默认脚本"这种兜底——
 *       Lua 脚本承载的是数据正确性（窗口修剪、过期），静默用错脚本比启动失败危险得多。</li>
 *   <li>结果按"路径 + 返回值类型"缓存：{@link DefaultRedisScript} 构造后不可变，
 *       缓存可避免重复读 classpath；同时脚本对象内部会缓存 SHA，便于 EVALSHA。</li>
 * </ul>
 *
 * <h2>返回值类型必须显式声明</h2>
 * 例如写入脚本返回 {@code LLEN}（整数），就要声明成 {@code Long.class}。
 * 若用 {@code Object.class}，Spring Data Redis 会按默认序列化器去反序列化返回值，
 * 整数会被当成 JSON 解析而报错或得到意外结果。
 */
public final class LuaScriptLoader {

    /** key = classpath 路径 + '#' + 返回值类型全名 */
    private static final Map<String, Object> CACHE = new ConcurrentHashMap<>();

    private LuaScriptLoader() {
        // 工具类，禁止实例化
    }

    /**
     * 加载 Lua 脚本并包装为 {@link RedisScript}。
     *
     * @param classpathLocation classpath 相对路径，例如 {@code lua/chat_memory_write.lua}（不带前导斜杠）
     * @param resultType        脚本返回值类型，例如 {@code Long.class}
     * @return 可重复使用的脚本对象（内部已缓存）
     * @throws IllegalStateException 脚本不存在或读取失败——启动期即失败，不留到运行期
     */
    @SuppressWarnings("unchecked")
    public static <T> RedisScript<T> load(String classpathLocation, Class<T> resultType) {
        String cacheKey = classpathLocation + '#' + resultType.getName();
        RedisScript<?> cached = (RedisScript<?>) CACHE.get(cacheKey);
        if (cached != null) {
            return (RedisScript<T>) cached;
        }
        RedisScript<T> script = new DefaultRedisScript<>(read(classpathLocation), resultType);
        CACHE.put(cacheKey, script);
        return script;
    }

    private static String read(String classpathLocation) {
        ClassPathResource resource = new ClassPathResource(classpathLocation);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Redis Lua 脚本不存在: classpath:" + classpathLocation
                            + "（确认它位于 src/main/resources 下，且构建时被复制进 classes）");
        }
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 Redis Lua 脚本失败: classpath:" + classpathLocation, e);
        }
    }
}
