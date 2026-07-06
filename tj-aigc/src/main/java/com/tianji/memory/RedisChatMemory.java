package com.tianji.memory;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.collection.CollUtil;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.BoundListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * 基于 Redis 的聊天记忆实现
 *
 * Redis 数据结构设计：
 * - 数据结构：List（双向链表）
 * - Key 格式：CHAT:{conversationId}
 * - Value：JSON 序列化的 Message 对象列表
 * - 使用 rightPush 追加到尾部，左侧为最早消息，右侧为最新消息
 *
 * @Name: RedisChatMemory
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 17:51
 * @Description: Redis 实现的聊天记忆，支持多轮对话和分布式部署
 */
public class RedisChatMemory implements ChatMemory {

    public static final String DEFAULT_PREFIX = "CHAT:";

    private final String prefix;

    public RedisChatMemory() {
        this.prefix = DEFAULT_PREFIX;
    }

    public RedisChatMemory(String prefix) {
        this.prefix = prefix;
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 批量添加消息到指定会话
     *
     * @param conversationId 会话 ID（格式：用户ID_会话ID）
     * @param messages       需要添加的消息列表
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        if (CollUtil.isEmpty(messages)) {
            return;
        }
        String redisKey = getKey(conversationId);
        BoundListOperations<String, String> listOps = stringRedisTemplate.boundListOps(redisKey);
        // 将 conversationId传递给 toJson，用于序列化 AssistantMessage 时关联 ToolResultHolder 中的 params
        messages.forEach(message -> listOps.rightPush(MessageUtil.toJson(message, conversationId)));

        // TODO 可优化：设置过期时间，自动清理历史会话
        // stringRedisTemplate.expire(redisKey, 7, TimeUnit.DAYS);
    }

    /**
     * 获取指定会话的最近 N 条消息，按时间正序（最早在前，最新在后）
     *
     * @param conversationId 会话 ID
     * @param lastN          需要获取的消息数量
     * @return 消息列表
     */
    @Override
    public List<Message> get(String conversationId, int lastN) {
        if (lastN <= 0) {
            return List.of();
        }
        String redisKey = getKey(conversationId);
        BoundListOperations<String, String> listOps = stringRedisTemplate.boundListOps(redisKey);
        // range(0, lastN) 从左侧（最早消息）开始取，即每次获取最旧的聊天记忆
        List<String> messages = listOps.range(0, lastN);
        return CollStreamUtil.toList(messages, MessageUtil::toMessage);
    }

    /**
     * 清除指定会话的所有记忆
     *
     * @param conversationId 会话 ID
     */
    @Override
    public void clear(String conversationId) {
        stringRedisTemplate.delete(getKey(conversationId));
    }

    /**
     * 生成 Redis Key，格式：{prefix}{conversationId}
     */
    private String getKey(String conversationId) {
        return prefix + conversationId;
    }
}
