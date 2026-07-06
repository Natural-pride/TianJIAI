package com.tianji.service.impl;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.IdUtil;
import com.tianji.config.SystemPromptConfig;
import com.tianji.config.ToolResultHolder;
import com.tianji.constants.Constant;
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

    // 聊天客户端
    private final ChatClient chatClient;

    // 系统提示词配置
    private final SystemPromptConfig systemPromptConfig;

    // 生成请求id
    String requestId = IdUtil.fastSimpleUUID();

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
    // 输出结束的标记
    private static final ChatEventVO STOP_EVENT = ChatEventVO
            .builder()
            .eventType(ChatEventTypeEnum.STOP.getValue()).build();

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
                .toolContext(MapUtil.<String,Object> builder() // 创建一个 Map 构建器
                        .put(Constant.REQUEST_ID,requestId) // 添加请求 ID
                        .build()
                ) // 构建 Map
                .user(question)// 2.3 设置用户问题
                .stream() // 2.4 开启流式输出模式
                .chatResponse()  // 2.5 获取 ChatResponse 流（包含元数据信息）

                // 3. Reactor 流式处理

                // 3.1 请求大模型前，标记该会话正在生成
                // 前端"停止生成"按钮会触发 stop 方法，移除此标记
                .doFirst(() -> {
                    GENERATE_STATUS.put(sessionId, true); // 将 sessionId 对应的值设为 true，表示正在生成
                })

                // 3.2 大模型输出完成，清除生成状态
                .doOnComplete(() -> {
                    GENERATE_STATUS.remove(sessionId);  // 移除 sessionId 对应的值，表示生成完成
                })
                .doOnError(throwable -> { // 3.3 大模型输出异常，清除生成状态
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
                .concatWith(Flux.defer(() -> {
                    // 通过请求id获取到参数列表，如果不为空，就将其追加到返回结果中
                    Map<String,Object> map = ToolResultHolder.get(requestId);
                    if (CollUtil.isNotEmpty(map)) {
                        ToolResultHolder.remove(requestId); // 清除参数列表，以避免oom，放在这里是合适的，因为concatWith不管有没有什么异常情况，都会执行
                        // 响应给前端的参数数据
                        ChatEventVO chatEventVO = ChatEventVO.builder()
                                .eventData(map)
                                .eventType(ChatEventTypeEnum.PARAM.getValue())
                                .build();
                        return Flux.just(chatEventVO, STOP_EVENT);
                    }
                    return Flux.just(STOP_EVENT);
                }));

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
