package com.llm.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.request.ReleaseCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * <h2>手动管理 Milvus Collection Schema（SDK 2.5.9）</h2>
 * <p>
 * 相比于 LangChain4j 的 {@code MilvusEmbeddingStore} 自动建表，
 * 这个配置类手动创建 Collection，可以在 Schema 中定义自定义标量字段
 * （如多租户的 {@code userId}）并为其建立索引。
 *
 * <h3>Schema 结构（5 个字段）</h3>
 * <pre>
 * ┌──────────┬───────────────┬─────────────────────────────────┐
 * │  字段名   │   类型          │   说明                          │
 * ├──────────┼───────────────┼─────────────────────────────────┤
 * │ id       │ VarChar(128)  │ 主键，文档块唯一标识               │
 * │ text     │ VarChar(65535)│ 原始文本（LangChain4j 写入）       │
 * │ metadata │ JSON          │ 元数据（LangChain4j 写入 JSON）    │
 * │ userId   │ VarChar(256)  │ ★ 多租户标量字段，有独立索引        │
 * │ vector   │ FloatVector   │ 512 维向量（BGE-small-zh）        │
 * └──────────┴───────────────┴─────────────────────────────────┘
 * </pre>
 *
 * <h3>两种过滤方式对比</h3>
 * <pre>
 * 方式 A（JSON 字段）：metadata["userId"] == "123"
 *    → LangChain4j 的 {@code metadataKey("userId").isEqualTo("123")}
 *    → 无需改动插入逻辑，简单
 *
 * 方式 B（独立标量字段）：userId == "123"
 *    → 原生 Milvus 标量过滤，性能更好（有索引）
 *    → 需要手动插入 userId 到列字段
 * </pre>
 *
 * <h3>与 LangChain4j 的协作</h3>
 * <ol>
 *   <li>本配置先执行，手动建好 Collection（含 userId 字段）</li>
 *   <li>LangChain4j 的 {@code MilvusEmbeddingStore} 初始化时发现 Collection 已存在，跳过建表</li>
 *   <li>{@code EmbeddingStoreIngestor} 写入时只填充 id / text / metadata / vector，不写 userId 字段</li>
 *   <li>userId 信息通过 document.metadata 存入 JSON，filter 使用方式 A</li>
 *   <li>如需方式 B（独立标量过滤），需自定义插入逻辑同时写入 userId 列</li>
 * </ol>
 *
 * @see EasyRAGContentRetriever 查询侧的 dynamicFilter
 * @see EasyRAGEmbeddingStoreConfig 文档摄入侧
 */
@Configuration
public class MilvusManualCollectionConfig {

    @Value("${langchain4j.milvus.host}")
    private String host;

    @Value("${langchain4j.milvus.port}")
    private int port;

    @Value("${langchain4j.milvus.database-name}")
    private String databaseName;

    @Value("${langchain4j.milvus.collection-name}")
    private String collectionName;

    @Value("${langchain4j.milvus.dimension}")
    private int dimension;

    /**
     * 是否在启动时重建 Collection（true = 删旧建新，false = 不存在才建）。
     * 建议在 application.yaml 中配置：{@code langchain4j.milvus.recreate-collection: false}
     */
    @Value("${langchain4j.milvus.recreate-collection:false}")
    private boolean recreateCollection;

    /** IVF nlist 参数：聚类中心数，推荐 4 × sqrt(总文档数) */
    private static final int NLIST = 128;

    // ======================== Milvus 客户端 ========================

    /**
     * Milvus SDK v2 原生客户端，用于手动管理 Collection Schema。
     * 注意：日常 RAG 检索仍走 LangChain4j，此客户端仅用于 Schema 管理。
     */
    @Bean
    public MilvusClientV2 milvusClientV2() {
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .dbName(databaseName)
                .build();
        MilvusClientV2 client = new MilvusClientV2(config);
        System.out.println("🔗 Milvus 原生客户端已连接: " + host + ":" + port + " / " + databaseName);
        return client;
    }

    // ======================== Collection 初始化 ========================

    /**
     * 初始化 Collection Schema，确保包含 userId 标量字段及其索引。
     * <p>
     * SDK 2.5.9 关键 API：
     * <ul>
     *   <li>{@code hasCollection()} 返回 {@code Boolean}（不是 R 包装类）</li>
     *   <li>Schema 使用 {@code CreateCollectionReq.FieldSchema} 定义字段</li>
     *   <li>{@code CollectionSchema.builder().fieldSchemaList(List)} 设置字段列表</li>
     * </ul>
     */
    @Bean
    public String initMilvusCollection(MilvusClientV2 client) {
        // -- 步骤 0：如需重建，先删 --
        if (recreateCollection) {
            dropIfExists(client, collectionName);
        }

        // -- 步骤 1：检查是否已存在（返回 Boolean，不是 R 包装类） --
        HasCollectionReq hasReq = HasCollectionReq.builder()
                .collectionName(collectionName)
                .build();

        if (Boolean.TRUE.equals(client.hasCollection(hasReq))) {
            System.out.println("📦 Milvus Collection 已存在，跳过创建: " + collectionName);
            return "EXISTING";
        }

        // -- 步骤 2：定义 Schema 字段 --
        // SDK 2.5.9 使用 FieldSchema（嵌套在 CreateCollectionReq 中），而非 AddFieldReq
        CreateCollectionReq.FieldSchema idField = CreateCollectionReq.FieldSchema.builder()
                .name("id")
                .dataType(DataType.VarChar)
                .maxLength(128)
                .isPrimaryKey(Boolean.TRUE)
                .autoID(Boolean.FALSE)
                .description("主键：文档块的唯一标识，格式如 doc_source_chunk_0")
                .build();

        CreateCollectionReq.FieldSchema textField = CreateCollectionReq.FieldSchema.builder()
                .name("text")
                .dataType(DataType.VarChar)
                .maxLength(65535)
                .description("原始文本内容")
                .build();

        CreateCollectionReq.FieldSchema metadataField = CreateCollectionReq.FieldSchema.builder()
                .name("metadata")
                .dataType(DataType.JSON)
                .description("JSON 元数据，LangChain4j 自动写入，如 {doc_source, userId, ...}")
                .build();

        CreateCollectionReq.FieldSchema userIdField = CreateCollectionReq.FieldSchema.builder()
                .name("userId")                        // ★ 多租户标量字段
                .dataType(DataType.VarChar)
                .maxLength(256)
                .description("多租户标识：所属用户的 ID，用于快速标量过滤")
                .build();

        CreateCollectionReq.FieldSchema vectorField = CreateCollectionReq.FieldSchema.builder()
                .name("vector")
                .dataType(DataType.FloatVector)
                .dimension(dimension)
                .description("文本向量（BGE-small-zh 输出 512 维）")
                .build();

        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .fieldSchemaList(Arrays.asList(idField, textField, metadataField, userIdField, vectorField))
                .build();

        // -- 步骤 3：创建 Collection --
        CreateCollectionReq createReq = CreateCollectionReq.builder()
                .collectionName(collectionName)
                .collectionSchema(schema)
                .description("RAG 知识库向量集合 - 手动 Schema 版本")
                .build();

        client.createCollection(createReq);
        System.out.println("✅ Milvus Collection 创建成功: " + collectionName + " (dimension=" + dimension + ")");

        // -- 步骤 4：创建向量索引 --
        IndexParam vectorIndex = IndexParam.builder()
                .fieldName("vector")
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("nlist", String.valueOf(NLIST)))
                .build();

        client.createIndex(CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(List.of(vectorIndex))
                .build());
        System.out.println("✅ 向量索引创建成功: IVF_FLAT + COSINE (nlist=" + NLIST + ")");

        // -- 步骤 5：创建 userId 标量索引（多租户快速过滤的关键） --
        IndexParam userIdIndex = IndexParam.builder()
                .fieldName("userId")
                .indexType(IndexParam.IndexType.STL_SORT)  // STL_SORT: 字符串排序索引
                .build();

        client.createIndex(CreateIndexReq.builder()
                .collectionName(collectionName)
                .indexParams(List.of(userIdIndex))
                .build());
        System.out.println("✅ userId 标量索引创建成功: STL_SORT");

        // -- 步骤 6：加载到内存 --
        client.loadCollection(LoadCollectionReq.builder()
                .collectionName(collectionName)
                .build());
        System.out.println("🚀 Collection 已加载到内存: " + collectionName);

        return "CREATED";
    }

    // ======================== 辅助方法 ========================

    private void dropIfExists(MilvusClientV2 client, String name) {
        HasCollectionReq hasReq = HasCollectionReq.builder().collectionName(name).build();
        if (Boolean.TRUE.equals(client.hasCollection(hasReq))) {
            client.releaseCollection(ReleaseCollectionReq.builder().collectionName(name).build());
            client.dropCollection(DropCollectionReq.builder().collectionName(name).build());
            System.out.println("🗑️ 旧 Collection 已删除: " + name);
        }
    }
}
