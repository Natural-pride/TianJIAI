package com.tianji.memory;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 聊天记忆接口，定义多轮对话记忆的存储和检索操作
 * 继承 Spring AI 的 ChatMemory 接口，使自定义实现与框架原生兼容
 */
public interface ChatMemory extends org.springframework.ai.chat.memory.ChatMemory {

    /**
     * 便捷方法：添加单条消息到指定会话
     * 默认实现为将单条消息包装为 List，然后调用批量添加方法
     *
     * @param conversationId 会话 ID
     * @param message        单条聊天消息
     */
    default void add(String conversationId, Message message) {
        this.add(conversationId, List.of(message));
    }

    /**
     * 批量添加消息到指定会话
     *
     * @param conversationId 会话 ID
     * @param messages       需要添加的消息列表
     */
    void add(String conversationId, List<Message> messages);

    /**
     * 获取指定会话的最近 N 条消息，按时间正序（最早在前）
     *
     * @param conversationId 会话 ID
     * @param lastN          需要获取的消息数量
     * @return 消息列表，数量不超过 lastN
     */
    List<Message> get(String conversationId, int lastN);

    /**
     * 清除指定会话的所有记忆
     *
     * @param conversationId 会话 ID
     */
    void clear(String conversationId);

}
