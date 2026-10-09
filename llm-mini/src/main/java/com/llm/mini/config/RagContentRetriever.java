package com.llm.mini.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.logical.And;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * RAG 内容检索器：把查询文本向量化后，在 Milvus 中做相似度检索。
 *
 * <pre>
 * queryText → BGE-small-zh 向量化（512 维）→ Milvus 相似度检索 Top-{@value #MAX_RESULTS}
 *           → 丢掉相似度 &lt; {@value #MIN_SCORE} 的结果 → 返回 Content 列表
 * </pre>
 *
 * <h2>多租户隔离</h2>
 * 向量在摄入时已打上 {@code userId} / {@code agentId} 标签（见 {@code EmbeddingStoreConfig}），
 * 这里按这两个标签拼 {@code AND} 过滤，保证"每个用户/智能体只检索到自己的知识库"。
 *
 * <h2>为什么要显式传 userId / agentId，而不是用 dynamicFilter</h2>
 * 原先实现的是 {@code ContentRetriever} 接口，靠
 * {@code query.metadata().invocationParameters()} 读取 {@code @V("userId")}/{@code @V("agentId")}，
 * 那只有走 {@code .retrievalAugmentor(...)} 时才成立。<b>而 augmentor 会把注入结果
 * 写进会话记忆</b>（实测：检索到的文档被固化进历史、之后每轮重复发送），
 * 所以已改为在 {@code chatRequestTransformer} 里手动调用本类——
 * 那里能直接拿到 memoryId，从中解析身份即可，不再需要 {@code dynamicFilter} 这层间接。
 * <p>
 * 顺带的好处：依赖从"框架传入的 Query 元数据"变成"两个显式入参"，可读性与可测性都更好。
 */
@Component
public class RagContentRetriever {

    /** 最多返回几条匹配片段 */
    public static final int MAX_RESULTS = 3;

    /** 相似度阈值：低于它的结果直接丢弃 */
    public static final double MIN_SCORE = 0.6;

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    public RagContentRetriever(EmbeddingStore<TextSegment> embeddingStore,
                               EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 检索与 {@code queryText} 相关的知识库片段。
     *
     * @param queryText 查询文本（通常是用户这一轮的问题）
     * @param userId    用户 ID，用于元数据过滤；为 null 则该维度不过滤
     * @param agentId   智能体 ID，用于元数据过滤；为 null 则该维度不过滤
     * @return 命中的内容片段（可能为空），已按相似度从高到低排列
     */
    public List<Content> retrieve(String queryText, Long userId, Long agentId) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        Embedding queryEmbedding = embeddingModel.embed(queryText).content();

        // 注意：内嵌 Builder 的类型名是 Lombok 生成的 EmbeddingSearchRequestBuilder（不是 Builder）
        EmbeddingSearchRequest.EmbeddingSearchRequestBuilder requestBuilder = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(MAX_RESULTS)
                .minScore(MIN_SCORE);

        Filter filter = buildIsolationFilter(userId, agentId);
        if (filter != null) {
            requestBuilder.filter(filter);
        }

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(requestBuilder.build());
        return result.matches().stream()
                .map(match -> Content.from(match.embedded()))
                .collect(Collectors.toList());
    }

    /**
     * 拼多租户隔离过滤器：{@code metadata["userId"]==x AND metadata["agentId"]==y}。
     * 两个维度都没有时返回 {@code null}（表示不过滤）。
     */
    private Filter buildIsolationFilter(Long userId, Long agentId) {
        Filter filter = null;
        if (userId != null) {
            filter = metadataKey("userId").isEqualTo(String.valueOf(userId));
        }
        if (agentId != null) {
            Filter agentFilter = metadataKey("agentId").isEqualTo(String.valueOf(agentId));
            filter = (filter == null) ? agentFilter : new And(filter, agentFilter);
        }
        return filter;
    }
}
