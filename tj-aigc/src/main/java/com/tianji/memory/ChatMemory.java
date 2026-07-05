package com.tianji.memory;




import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * 聊天记忆接口
 * 定义多轮对话记忆的存储和检索操作
 * 
 * 设计说明：
 * 1. 提供单条/批量添加消息的接口
 * 2. 支持获取最近 N 条消息（滑动窗口）
 * 3. 支持清除指定会话的所有记忆
 * 
 * 接口的默认方法：
 * - add(String, Message)：便捷方法，单条消息自动包装为 List 调用批量方法
 *   （使用 Java 8 default 方法，减少实现类的重复代码）
 * 
 * 流式场景思考：
 * - 当前接口是同步阻塞的，在 Reactor 异步流中调用会占用线程
 * - 理想情况下应提供返回 Mono/Flux 的异步版本，但 Spring AI 框架目前只支持阻塞接口
 * - 后续可考虑自定义 ReactiveChatMemory 接口以适配响应式编程
 *
 * 注意：继承 Spring AI 的 ChatMemory 接口，使自定义接口与 Spring AI 框架原生兼容，
 * 避免按类型注入时因接口不兼容导致 Bean 找不到。
 */
public interface ChatMemory extends org.springframework.ai.chat.memory.ChatMemory {

    /**
     * 便捷方法：添加单条消息到指定会话
     * 默认实现为将单条消息包装为 List，然后调用批量添加方法
     * 实现类可选择重写此方法以优化性能（如直接存储，避免创建临时 List）
     *
     * @param conversationId 会话 ID
     * @param message        单条聊天消息
     */
    default void add(String conversationId, Message message) {
        this.add(conversationId, List.of(message));
    }

    /**
     * 批量添加消息到指定会话（核心方法）
     * 实现类必须提供此方法的线程安全实现
     *
     * @param conversationId 会话 ID
     * @param messages       需要添加的消息列表
     */
    void add(String conversationId, List<Message> messages);

    /**
     * 获取指定会话的最近 N 条消息
     * 用于构建多轮对话上下文，返回的消息按时间正序（最早在前）
     *
     * @param conversationId 会话 ID
     * @param lastN          需要获取的消息数量（如最近 10 轮 = 20 条消息）
     * @return 消息列表，数量不超过 lastN
     */
    List<Message> get(String conversationId, int lastN);

    /**
     * 清除指定会话的所有记忆
     * 用于用户删除会话或会话过期清理
     *
     * @param conversationId 会话 ID
     */
    void clear(String conversationId);

}