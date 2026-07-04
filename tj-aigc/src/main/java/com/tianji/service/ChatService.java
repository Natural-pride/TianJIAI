package com.tianji.service;


import com.tianji.common.utils.UserContext;
import com.tianji.vo.ChatEventVO;
import reactor.core.publisher.Flux;

/**
 * 聊天服务接口
 * 定义 AI 聊天的核心业务操作，包括流式聊天和停止聊天
 * 
 * 实现要求：
 * - chat 方法必须返回 Flux<ChatEventVO>，支持 SSE 流式响应
 * - stop 方法必须能够中断正在进行的流式输出
 * 
 * @Name: ChatService
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 16:18
 * @Description: 聊天服务接口，定义流式聊天相关操作
 */

public interface ChatService {

    /**
     * 流式聊天：提交用户问题并通过 SSE 流持续推送 AI 回复片段
     * 
     * @param question  用户输入的聊天内容
     * @param sessionId 会话 ID，用于关联同一会话的多轮消息
     * @return SSE 事件流，包含 DATA 事件和最终的 STOP 事件
     */
    Flux<ChatEventVO> chat(String question, String sessionId);

    /**
     * 停止聊天：中断当前正在进行的 AI 生成
     * 实现原理：清除生成状态标记，触发 takeWhile 停止流式输出
     * 
     * @param sessionId 需要停止的会话 ID
     */
    void stop(String sessionId);

    /**
     * 获取对话 ID，规则：用户id_会话id
     * 
     * 用于 RedisChatMemory 的 key，实现多用户多会话隔离
     * 示例：用户 ID=1，会话 ID=abc → 返回 "1_abc"
     *
     * @param sessionId 会话 id
     * @return 拼接后的对话 id
     */
    static String getConversationId(String sessionId) {
        return UserContext.getUser() + "_" + sessionId;
    }
}
