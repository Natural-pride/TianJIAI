package com.tianji.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.IdUtil;
import com.tianji.common.utils.UserContext;
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
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天服务实现，集成 Spring AI 实现流式多轮对话
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

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final SystemPromptConfig systemPromptConfig;
    private final VectorStore vectorStore;

    // 存储大模型生成状态的线程安全 Map，key 为 sessionId
    private static final Map<String, Boolean> GENERATE_STATUS = new ConcurrentHashMap<>();

    // 输出结束标记
    private static final ChatEventVO STOP_EVENT = ChatEventVO
            .builder()
            .eventType(ChatEventTypeEnum.STOP.getValue()).build();

    /**
     * 聊天：提交用户问题并获取大模型响应
     * @param question  用户输入的聊天内容
     * @param sessionId 会话 ID，用于关联同一会话的多轮消息
     * @return 聊天响应的事件流
     */
    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        // 生成对话 ID，格式：用户ID_会话ID，用于 RedisChatMemory 的 key，实现多用户多会话的对话记忆隔离
        String conversationId = ChatService.getConversationId(sessionId);

        // 生成请求id，用于关联本次请求中工具调用产生的额外参数（eventType=1003）
        String requestId = IdUtil.fastSimpleUUID();

        Long userId = UserContext.getUser();

        // 将 requestId 以 conversationId 为 key 存入 ToolResultHolder
        // RedisChatMemory 序列化 AssistantMessage 时，通过 conversationId → requestId → params 写入 RedisMessage.params
        ToolResultHolder.put(conversationId, Constant.REQUEST_ID, requestId);

        // 大模型输出内容的缓存器，用于输出中断后保存到历史记录
        StringBuilder outputBuilder = new StringBuilder();

        return chatClient.prompt()
                // 注入系统提示词和当前时间，提示词模板从 Nacos 加载，支持 {{now}} 占位符
                .system(promptSystem -> promptSystem
                        .text(systemPromptConfig.getChatSystemMessage().get())
                        .param("now", DateUtil.now()))
                // 注入多轮对话记忆的 conversationId，MessageChatMemoryAdvisor 会根据此 ID 从 Redis 获取历史对话
                .advisors(advisor -> advisor
                        // 设置 RAG 查询
                        .advisors(new QuestionAnswerAdvisor(vectorStore, SearchRequest.builder().query(question).topK(5).similarityThreshold(0.5).build()))
                        .param(
                                AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY,
                                conversationId))
                .toolContext(MapUtil.<String, Object>builder()
                        .put(Constant.REQUEST_ID, requestId)
                        .put(Constant.USER_ID, userId)
                        .build())
                .user(question)
                .stream()
                .chatResponse()
                // 标记该会话正在生成，前端"停止生成"按钮会触发 stop 方法移除标记
                .doFirst(() -> GENERATE_STATUS.put(sessionId, true))
                // 大模型输出完成或异常，清除生成状态
                .doOnComplete(() -> GENERATE_STATUS.remove(sessionId))
                .doOnError(throwable -> GENERATE_STATUS.remove(sessionId))
                // 输出被取消时，保存已输出的内容到历史记录
                .doOnCancel(() -> saveStopHistoryRecord(conversationId, outputBuilder.toString()))
                // 根据生成状态控制是否继续输出，状态被移除（null/false）则停止流
                .takeWhile(s -> Optional.ofNullable(GENERATE_STATUS.get(sessionId)).orElse(false))
                // 将每个 ChatResponse chunk 转换为前端可消费的 DATA 事件
                .map(chatResponse -> {
                    String content = chatResponse.getResult().getOutput().getText();
                    outputBuilder.append(content);
                    return ChatEventVO.builder()
                            .eventData(content)
                            .eventType(ChatEventTypeEnum.DATA.getValue())
                            .build();
                })
                .concatWith(Flux.defer(() -> {
                    // 通过请求id获取工具调用参数，追加到返回结果中
                    Map<String, Object> map = ToolResultHolder.get(requestId);
                    if (CollUtil.isNotEmpty(map)) {
                        // 清除参数列表以避免 OOM，放在 concatWith 里确保无论是否异常都会执行
                        ToolResultHolder.remove(requestId);
                        ChatEventVO chatEventVO = ChatEventVO.builder()
                                .eventData(map)
                                .eventType(ChatEventTypeEnum.PARAM.getValue())
                                .build();
                        return Flux.just(chatEventVO, STOP_EVENT);
                    }
                    return Flux.just(STOP_EVENT);
                }))
                // 流结束后清理 conversationId → requestId 的映射，避免内存泄漏
                // 放在 concatWith 之后，确保 RedisChatMemory 序列化时已经取到了 requestId
                .doFinally(signal -> ToolResultHolder.remove(conversationId));
    }

    /**
     * 停止聊天：中断当前正在进行的 AI 生成
     * 实现原理：从 GENERATE_STATUS Map 中移除 sessionId 对应的标记，
     *          takeWhile 检测到状态为 null/false 后，立即停止流式输出
     *
     * @param sessionId 需要停止的会话 ID
     */
    @Override
    public void stop(String sessionId) {
        GENERATE_STATUS.remove(sessionId);
    }

    /**
     * 保存停止输出的记录
     *
     * @param conversationId 会话id
     * @param content        大模型输出的内容
     */
    private void saveStopHistoryRecord(String conversationId, String content) {
        chatMemory.add(conversationId, new AssistantMessage(content));
    }
}
