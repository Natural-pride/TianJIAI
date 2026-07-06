package com.tianji.controller;

import cn.hutool.core.collection.CollStreamUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/embedding")
@RequiredArgsConstructor
public class EmbeddingController {

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    @PostMapping
    public void saveVectorStore(@RequestParam("messages") List<String> messages) {
        log.info("保存到向量数据库中，消息数据：{}", messages);
        //构建文档
        List<Document> documents = CollStreamUtil.toList(messages, message -> Document.builder()
                .text(message)
                .build());
        //存储到向量数据库中
        vectorStore.add(documents);
        log.info("保存到向量数据库成功, 数量：{}", messages.size());
    }

    @GetMapping
    public EmbeddingResponse embed(@RequestParam("message") String message) {
        // 根据文本得到一个向量对象
        return embeddingModel.embedForResponse(List.of(message));
    }

    @DeleteMapping
    public void deleteVectorStore(@RequestParam("ids") List<String> ids) {
        // 删除向量数据库中的数据
        vectorStore.delete(ids);
    }

    @GetMapping("/search")
    public List<Document> search(@RequestParam("message") String message) {
        return vectorStore.similaritySearch(SearchRequest.builder().query(message).topK(5).build());
    }

    @GetMapping("/search/all")
    public List<Document> searchAll() {
        // 搜索全部数据
        return vectorStore.similaritySearch(SearchRequest.builder().query("").topK(999).build());
    }

}