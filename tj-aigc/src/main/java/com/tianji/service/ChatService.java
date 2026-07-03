package com.tianji.service;


import com.tianji.vo.ChatEventVO;
import reactor.core.publisher.Flux;

/**
 * @Name: ChatService
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 16:18
 * @Description:
 */

public interface ChatService {

    // 流式聊天
    Flux<ChatEventVO> chat(String question, String sessionId);

    // 停止聊天
    void stop(String sessionId);
}
