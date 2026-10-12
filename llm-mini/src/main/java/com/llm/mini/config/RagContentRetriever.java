package com.llm.mini.config;

import com.llm.mini.rag.DocumentVisibility;
import com.llm.mini.rag.RagMetadataKeys;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Or;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * RAG 内容检索器：把查询文本向量化后在 Milvus 做相似度检索。
 *
 * queryText → BGE 向量化(512维) → Milvus Top-{@value #MAX_RESULTS}
 *           → 丢掉相似度低于 {@value #MIN_SCORE} 的 → 返回 Content
 *
 * 多租户隔离：向量在摄入时已打 userId / agentId / visibility 标签，
 * 这里拼过滤条件，保证每个用户只检索到自己的知识库（详见 buildIsolationFilter）。
 *
 * 为什么显式传 userId/agentId 而不是用 dynamicFilter：原先靠
 * query.metadata().invocationParameters() 读 @V 传进来的身份，那只有走
 * .retrievalAugmentor(...) 时才成立。而 augmentor 会把注入结果写进会话记忆
 * （实测：检索到的文档被固化进历史、之后每轮重发），所以改成在
 * chatRequestTransformer 里手动调用本类——那里能直接拿到 memoryId 解析身份。
 * 顺带好处：依赖变成两个显式入参，可读性和可测性都更好。
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

        // 隔离过滤器<b>必然存在</b>：buildIsolationFilter 在拿不到身份时直接抛异常，不会返回 null。
        // 这里刻意不写 "filter != null 才 set" 的判空——那正是
        // "拿不到身份就退化成不过滤（跨租户可读）"的入口。
        // 注意：内嵌 Builder 的类型名是 Lombok 生成的 EmbeddingSearchRequestBuilder（不是 Builder）
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(MAX_RESULTS)
                .minScore(MIN_SCORE)
                .filter(buildIsolationFilter(userId, agentId))
                .build();

        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);
        return result.matches().stream()
                .map(match -> Content.from(match.embedded()))
                .collect(Collectors.toList());
    }

    /**
     * 拼多租户隔离过滤器：
     * userId == 当前用户 AND ( agentId == 当前智能体 OR visibility == 'USER' )
     *
     * userId 是硬约束，任何情况都不跨用户。Or 只在同一用户内部放宽 agentId：
     * visibility=USER 的文档记的 agentId 是上传时那个智能体，当前智能体是别人时
     * 只能靠这一支捞回来，这也是"共享"功能生效的唯一途径（改成 AGENT 会让它静默失效）。
     *
     * 隔离边界一律 fail-closed：
     * - userId 缺失时抛异常，而不是返回 null。返回 null 会让调用方跳过 filter，
     *   变成"拿所有租户的向量来比"——最严重的越权。抛异常会被上层 try/catch
     *   降级成"本轮不做 RAG"，对话照常，宁可少检索也不能多看见。
     * - agentId 缺失时只放行 visibility=USER（用户主动共享的），不放行任何 AGENT 私有文档。
     */
    private Filter buildIsolationFilter(Long userId, Long agentId) {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "RAG 检索必须提供 userId（多租户隔离硬约束）；拒绝在无身份时检索，避免退化成不过滤");
        }
        if (agentId == null) {
            return new And(
                    metadataKey(RagMetadataKeys.USER_ID).isEqualTo(String.valueOf(userId)),
                    metadataKey(RagMetadataKeys.VISIBILITY).isEqualTo(DocumentVisibility.USER.name()));
        }
        Filter agentOrShared = new Or(
                metadataKey(RagMetadataKeys.AGENT_ID).isEqualTo(String.valueOf(agentId)),
                metadataKey(RagMetadataKeys.VISIBILITY).isEqualTo(DocumentVisibility.USER.name()));
        return new And(
                metadataKey(RagMetadataKeys.USER_ID).isEqualTo(String.valueOf(userId)),
                agentOrShared);
    }
}
