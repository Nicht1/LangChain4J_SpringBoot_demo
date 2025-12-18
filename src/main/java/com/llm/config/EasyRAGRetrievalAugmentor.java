//package com.llm.config;
//
//import dev.langchain4j.data.message.ChatMessage;
//import dev.langchain4j.data.message.SystemMessage;
//import dev.langchain4j.data.message.UserMessage;
//import dev.langchain4j.rag.AugmentationRequest;
//import dev.langchain4j.rag.AugmentationResult;
//import dev.langchain4j.rag.RetrievalAugmentor;
//import dev.langchain4j.rag.content.Content;
//import dev.langchain4j.rag.content.retriever.ContentRetriever;
//import dev.langchain4j.rag.query.Query;
//import org.springframework.stereotype.Component;
//
//import java.util.List;
//import java.util.stream.Collectors;
//
///**
// * 自定义 RAG 增强器 - 将检索内容注入到 SystemMessage
// */
//@Component
//public class EasyRAGRetrievalAugmentor implements RetrievalAugmentor {
//
//    private final ContentRetriever contentRetriever;
//
//    public EasyRAGRetrievalAugmentor(ContentRetriever easyRAGcontentRetriever) {
//        this.contentRetriever = easyRAGcontentRetriever;
//    }
//
//    @Override
//    public AugmentationResult augment(AugmentationRequest augmentationRequest) {
//        ChatMessage chatMessage = augmentationRequest.chatMessage();
//
//        // 只处理 UserMessage
//        if (!(chatMessage instanceof UserMessage userMessage)) {
//            // 如果不是 UserMessage，直接返回原消息
//            return AugmentationResult.builder()
//                    .chatMessage(chatMessage)
//                    .contents(List.of())
//                    .build();
//        }
//
//        // 获取用户查询文本
//        String queryText = userMessage.singleText();
//
//        // 构建查询对象
//        Query query = Query.from(queryText, augmentationRequest.metadata());
//
//        // 执行检索
//        List<Content> retrievedContents = contentRetriever.retrieve(query);
//
//        // 如果没有检索到内容，返回原 UserMessage
//        if (retrievedContents == null || retrievedContents.isEmpty()) {
//            System.out.println("RAG: 未检索到相关文档");
//            return AugmentationResult.builder()
//                    .chatMessage(chatMessage)
//                    .contents(List.of())
//                    .build();
//        }
//
//        System.out.println("RAG: 检索到 " + retrievedContents.size() + " 个相关文档");
//
//        // 格式化检索内容
//        String formattedContents = retrievedContents.stream()
//                .map(content -> content.textSegment().text())
//                .collect(Collectors.joining("\n\n---\n\n"));
//
//        // 创建包含检索内容的 SystemMessage
//        String ragSystemPrompt = """
//                === 知识库相关文档 ===
//
//                """ + formattedContents + """
//
//
//                === 回答指引 ===
//                请基于上述文档内容回答用户的问题。
//                如果文档中没有相关信息，可以使用通用知识。
//                如果不确定答案，请明确告知用户。
//                """;
//
//        SystemMessage ragContextMessage = SystemMessage.from(ragSystemPrompt);
//
//        // 返回结果：将原 UserMessage 替换为 SystemMessage
//        // 注意：这里返回的 chatMessage 会被插入到消息列表中
//        return AugmentationResult.builder()
//                .chatMessage(ragContextMessage)  // 用 SystemMessage 替代
//                .contents(retrievedContents)
//                .build();
//    }
//}