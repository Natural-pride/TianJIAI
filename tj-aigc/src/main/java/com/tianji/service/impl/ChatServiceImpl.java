package com.tianji.service.impl;


import cn.hutool.core.date.DateUtil;
import com.tianji.config.SystemPromptConfig;
import com.tianji.enums.ChatEventTypeEnum;
import com.tianji.service.ChatService;
import com.tianji.vo.ChatEventVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天服务实现类
 * 
 * 核心职责：
 * 1. 调用 Spring AI 的 ChatClient，提交用户问题并获取大模型响应
 * 2. 通过 Reactor Flux 实现流式响应，实时推送 AI 回复片段
 * 3. 支持多轮对话记忆，通过 RedisChatMemory 关联历史上下文
 * 4. 提供停止生成功能，用户可随时中断 AI 输出
 * 
 * 技术架构：
 * - 响应式编程：使用 Project Reactor 的 Flux 处理流式数据
 * - 线程安全：ConcurrentHashMap 存储生成状态，支持多用户并发
 * - 配置驱动：系统提示词从 Nacos 动态加载，支持热更新
 * 
 * @Name: ChatServiceImpl
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 16:18
 * @Description: 聊天服务实现，集成 Spring AI 实现流式多轮对话
 */

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    /**
     * Spring AI 聊天客户端，用于调用大模型
     * 在 SpringAIConfig 中配置，已注入日志记录器和记忆顾问器
     */
    private final ChatClient chatClient;

    /**
     * 系统提示词配置类，从 Nacos 动态加载
     * 包含 AI 人设、能力范围、回答风格等提示词内容
     */
    private final SystemPromptConfig systemPromptConfig;

    /**
     * 存储大模型生成状态的线程安全 Map
     * Key：sessionId（会话ID）
     * Value：Boolean（true 表示正在生成，false/null 表示停止）
     * 
     * 设计说明：
     * - 使用 ConcurrentHashMap 保证多用户并发安全
     * - static 修饰，全局共享同一份状态
     * - 当前版本使用单机内存，分布式环境建议改用 Redis
     */
    private static final Map<String, Boolean> GENERATE_STATUS = new ConcurrentHashMap<>();

    /**
     * 流式聊天：将用户问题提交给大模型，并以 SSE 事件流方式持续推送 AI 回复片段
     * 
     * 执行流程：
     * 1. 生成 conversationId（用户ID_会话ID），用于多轮对话记忆隔离
     * 2. 调用 ChatClient 构建请求：
     *    - 注入系统提示词（从 Nacos 动态获取）+ 当前时间
     *    - 注入 conversationId，激活多轮对话记忆
     *    - 发送用户问题
     * 3. 开启流式输出，获取 ChatResponse 流
     * 4. Reactor 流式处理：
     *    - doFirst：标记生成开始（状态置为 true）
     *    - doOnComplete：标记生成完成（移除状态）
     *    - doOnError：标记生成失败（移除状态）
     *    - takeWhile：根据状态控制是否继续输出
     *    - map：将 ChatResponse 转换为 ChatEventVO（DATA 事件）
     *    - concatWith：追加 STOP 事件，通知流结束
     * 
     * @param question  用户输入的聊天内容
     * @param sessionId 会话 ID，用于关联同一会话的多轮消息
     * @return SSE 事件流：先输出多个 DATA 事件，最后输出 STOP 事件
     */
    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {

        // 1. 生成对话 ID，格式：用户ID_会话ID
        // 用于 RedisChatMemory 的 key，实现多用户多会话的对话记忆隔离
        String conversationId = ChatService.getConversationId(sessionId);

        // 2. 构建流式请求并返回事件流
        return chatClient.prompt()

                // 2.1 注入系统提示词和当前时间
                // 提示词模板从 Nacos 加载，支持 {{now}} 等占位符
                .system(promptSystem -> promptSystem
                        .text(systemPromptConfig.getChatSystemMessage().get())
                        .param("now", DateUtil.now()))

                // 2.2 注入多轮对话记忆的 conversationId
                // MessageChatMemoryAdvisor 会根据此 ID 从 Redis 获取历史对话
                .advisors(advisor -> advisor.param(
                        AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
                        conversationId))

                // 2.3 设置用户问题
                .user(question)

                // 2.4 开启流式输出模式
                .stream()

                // 2.5 获取 ChatResponse 流（包含元数据信息）
                .chatResponse()

                // 3. Reactor 流式处理

                // 3.1 请求大模型前，标记该会话正在生成
                // 前端"停止生成"按钮会触发 stop 方法，移除此标记
                .doFirst(() -> {
                    // 将 sessionId 对应的值设为 true，表示正在生成
                    GENERATE_STATUS.put(sessionId, true); // 标记生成开始
                })

                // 3.2 大模型输出完成，清除生成状态
                .doOnComplete(() -> {
                    // 移除 sessionId 对应的值，表示生成完成
                    GENERATE_STATUS.remove(sessionId); // 清除生成状态
                })

                // 3.3 大模型输出异常，清除生成状态
                .doOnError(throwable -> {
                    GENERATE_STATUS.remove(sessionId);
                })

                // 3.4 根据生成状态控制是否继续输出
                // 只要 sessionId 对应的状态为 true，就继续推送
                // 状态被移除（null）或变为 false，则立即停止流
                .takeWhile(s -> Optional.ofNullable(GENERATE_STATUS.get(sessionId)).orElse(false))

                // 3.5 将每个 ChatResponse chunk 转换为前端可消费的 DATA 事件
                .map(chatResponse -> {
                    // 提取 AI 回复的文本片段
                    String content = chatResponse.getResult().getOutput().getText();
                    return ChatEventVO.builder()
                            .eventData(content)
                            .eventType(ChatEventTypeEnum.DATA.getValue())
                            .build();
                })

                // 3.6 在流尾部追加一条 STOP 事件
                // 前端收到此事件后，关闭 SSE 连接，更新 UI 状态
                .concatWith(Flux.just(ChatEventVO.builder()
                        .eventType(ChatEventTypeEnum.STOP.getValue())
                        .build()));
    }

    /**
     * 停止聊天：中断当前正在进行的 AI 生成
     * 
     * 实现原理：
     * 从 GENERATE_STATUS Map 中移除 sessionId 对应的标记
     * takeWhile 检测到状态为 null/false 后，立即停止流式输出
     * 
     * @param sessionId 需要停止的会话 ID
     */
    @Override
    public void stop(String sessionId) {
        GENERATE_STATUS.remove(sessionId);
    }
}
