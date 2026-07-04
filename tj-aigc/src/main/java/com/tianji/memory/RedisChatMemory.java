package com.tianji.memory;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.BoundListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * @Name: RedisChatMemory
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 17:51
 * @Description: Redis实现的ChatMemory
 * TODO 待完善，并且对这个代码不理解？？？
 */

public class RedisChatMemory implements ChatMemory {

    // 默认redis中key的前缀
    public static final String DEFAULT_PREFIX = "CHAT:";

    private final String prefix;

    public RedisChatMemory() {
        this.prefix = DEFAULT_PREFIX;
    }

    public RedisChatMemory(String prefix) {
        this.prefix = prefix;
    }

    // 注入spring redis模板，进行redis的操作
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 将消息添加到指定会话的Redis列表中。
     *
     * @param conversationId 会话ID
     * @param messages       需要添加的消息列表
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        if (CollUtil.isEmpty(messages)) {
            // 如果消息列表为空则直接返回
            return;
        }
        String redisKey = getKey(conversationId);
        BoundListOperations<String, String> listOps  = stringRedisTemplate.boundListOps(redisKey);
        // 将消息序列化并添加到Redis列表的右侧
        messages.forEach(message -> listOps.rightPush(JSONUtil.toJsonStr(message)));
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        // 先不实现
        return List.of();
    }

    @Override
    public void clear(String conversationId) {
        String redisKey = getKey(conversationId);
        stringRedisTemplate.delete(redisKey);
    }

    private String getKey(String conversationId) {
        return prefix + conversationId;
    }
}