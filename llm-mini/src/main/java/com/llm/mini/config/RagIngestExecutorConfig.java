package com.llm.mini.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 文档摄入线程池。
 *
 * 摄入是秒级的（解析 + 分片 + BGE 向量化 + 写 Milvus），不能占 HTTP 线程，
 * 所以上传接口只落盘落表就返回 PENDING，前端轮询状态。
 *
 * 固定 2 线程：BGE 是 CPU 密集型，线程开多只会互相抢 CPU，还会拖慢聊天。
 * 有界队列 200：无界队列在大量上传时会一直堆积到 OOM 才暴露。
 * CallerRunsPolicy：队列满时由提交线程自己执行，形成背压——上传会变慢但不会丢任务。
 * 用守护线程：不阻止 JVM 退出。
 */
@Slf4j
@Configuration
public class RagIngestExecutorConfig {

    /** 摄入线程数：CPU 密集型，固定小池 */
    private static final int WORKER_COUNT = 2;

    /** 队列容量：超出后触发 CallerRunsPolicy（调用线程执行），形成背压 */
    private static final int QUEUE_CAPACITY = 200;

    @Bean(name = "ragIngestExecutor", destroyMethod = "shutdown")
    public ExecutorService ragIngestExecutor() {
        AtomicInteger seq = new AtomicInteger();
        ExecutorService executor = new ThreadPoolExecutor(
                WORKER_COUNT, WORKER_COUNT,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "rag-ingest-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        log.info("[llm.rag.ingest] 摄入线程池就绪: workers={} queueCapacity={} 拒绝策略=CallerRuns(背压)；"
                        + "上传与重试共用此池",
                WORKER_COUNT, QUEUE_CAPACITY);
        return executor;
    }
}
