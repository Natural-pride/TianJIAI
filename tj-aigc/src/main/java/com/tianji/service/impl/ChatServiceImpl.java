package com.tianji.service.impl;


import cn.hutool.core.date.DateUtil;
import com.tianji.config.SystemPromptConfig;
import com.tianji.enums.ChatEventTypeEnum;
import com.tianji.service.ChatService;
import com.tianji.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Name: ChatServiceImpl
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 16:18
 * @Description: 聊天服务实现
 */

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private final ChatClient chatClient;
    private final SystemPromptConfig systemPromptConfig;

    // 存储大模型的生成状态，这里采用ConcurrentHashMap是确保线程安全
    // 目前的版本暂时用Map实现，如果考虑分布式环境的话，可以考虑用redis来实现
    private static final Map<String, Boolean> GENERATE_STATUS = new ConcurrentHashMap<>();

    /**
     * 流式聊天：将用户问题提交给大模型，并以事件流的方式持续推送 AI 回复片段，
     * 最后再追加一条 STOP 结束事件，便于前端按 SSE / WebSocket 协议逐段渲染并在结束时关闭连接。
     *
     * @param question 用户输入的聊天内容
     * @param sessionId 会话 ID，用于关联同一会话的多轮消息（当前实现暂未使用，保留以备后续上下文扩展）
     * @return Flux<ChatEventVO> 响应事件流：先连续输出多条 DATA 事件（每条事件携带一段文本片段），
     *         随后输出一条 STOP 事件作为流终止标志
     */
    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        return chatClient.prompt()
                .system(promptSystem ->promptSystem
                        .text(systemPromptConfig.getChatSystemMessage().get()) // 系统提示词
                        .param("now", DateUtil.now())) // 当前时间
                .user(question) // 用户问题
                .stream() // 流式输出
                // TODO 为什么要这样写？chatResponse()
                .chatResponse() // 大模型响应
                .doFirst(()->{
                    // 请求大模型之前先打标记
                    GENERATE_STATUS.put(sessionId, true);
                })
                .doOnComplete(()->{
                    // 大模型输出完成，删除标记
                    GENERATE_STATUS.remove(sessionId);
                })
                .doOnError(throwable -> GENERATE_STATUS.remove(sessionId))  // 大模型输出错误，删除标记
                // 只要生成状态存在，就继续流式输出
                .takeWhile(s-> Optional.ofNullable(GENERATE_STATUS.get(sessionId)).orElse(false))
                // 将大模型流式输出的每个 chunk 转换为前端可消费的 DATA 事件
                .map(chatResponse -> { //
                    String content = chatResponse.getResult().getOutput().getText(); // 响应内容
                    return ChatEventVO.builder()
                            .eventData(content) // 事件数据
                            .eventType(ChatEventTypeEnum.DATA.getValue()) // 事件类型
                            .build();
                })
                // 在流尾部追加一条 STOP 事件，通知前端本轮对话已结束
                .concatWith(Flux.just(ChatEventVO.builder()
                        .eventType(ChatEventTypeEnum.STOP.getValue())
                        .build()));
    }

    @Override
    public void stop(String sessionId) {
        GENERATE_STATUS.remove(sessionId);
    }
}
