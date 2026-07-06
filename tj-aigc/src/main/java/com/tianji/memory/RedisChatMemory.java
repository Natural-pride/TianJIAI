package com.tianji.memory;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.redis.core.BoundListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 基于 Redis 的聊天记忆实现
 * 
 * 核心职责：
 * 1. 将聊天消息持久化到 Redis，支持服务重启后恢复会话历史
 * 2. 支持多实例部署（多服务共享 Redis，会话状态同步）
 * 3. 提供滑动窗口查询，获取最近 N 条消息用于多轮对话
 * 
 * Redis 数据结构设计：
 * - 数据结构：List（双向链表）
 * - Key 格式：CHAT:{conversationId}（如 CHAT:1_abc123）
 * - Value：JSON 序列化的 Message 对象列表
 * 
 * 消息存储顺序：
 * - 使用 rightPush 追加到 List 尾部（右侧）
 * - Left（头部）= 最早的消息
 * - Right（尾部）= 最新的消息
 * 
 * 多轮对话工作原理：
 * 1. 首次请求：get() 返回空 List，AI 无历史上下文
 * 2. 首轮对话：add() 保存用户问题 + AI 回答
 * 3. 后续请求：get() 返回最近 N 轮对话，AI 理解上下文继续对话
 * 
 * @Name: RedisChatMemory
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 17:51
 * @Description: Redis 实现的聊天记忆，支持多轮对话和分布式部署
 */

public class RedisChatMemory implements ChatMemory {

    /**
     * Redis Key 前缀
     * 格式：CHAT:{conversationId}
     * 示例 Key：CHAT:1_abc123
     */
    public static final String DEFAULT_PREFIX = "CHAT:";

    /**
     * Key 前缀实例变量，支持自定义前缀（如测试环境用 TEST:CHAT:）
     */
    private final String prefix;

    /**
     * 默认构造函数，使用默认前缀
     */
    public RedisChatMemory() {
        this.prefix = DEFAULT_PREFIX;
    }

    /**
     * 自定义前缀构造函数
     * 
     * @param prefix Redis Key 前缀，用于环境隔离
     */
    public RedisChatMemory(String prefix) {
        this.prefix = prefix;
    }

    /**
     * Spring Redis 模板，用于操作 Redis 数据结构
     * 使用 @Resource 注入，也可用 @Autowired
     */
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 批量添加消息到指定会话
     *
     * 执行流程：
     * 1. 空消息列表防御：如果 messages 为空直接返回，避免无用 Redis 操作
     * 2. 生成 Redis Key：prefix + conversationId
     * 3. 获取 List 操作器：BoundListOperations 绑定到指定 Key
     * 4. 序列化并追加：每条 Message 转为包装类 JSON，从右侧追加到 List
     *
     * Redis 命令对应：
     * RPUSH CHAT:123 '{"messageType":"USER","textContent":"你好"}'
     * RPUSH CHAT:123 '{"messageType":"ASSISTANT","textContent":"您好！"}'
     *
     * @param conversationId 会话 ID（格式：用户ID_会话ID）
     * @param messages       需要添加的消息列表（通常包含用户问题和 AI 回答）
     */
    @Override
    public void add(String conversationId, List<Message> messages) {
        // 防御：空消息列表不操作 Redis
        if (CollUtil.isEmpty(messages)) {
            return;
        }

        // 生成 Redis Key：如 CHAT:1_abc123
        String redisKey = getKey(conversationId);

        // 绑定 List 操作器（后续操作都针对此 Key）
        BoundListOperations<String, String> listOps = stringRedisTemplate.boundListOps(redisKey);

        // 遍历消息，逐条序列化并追加到 List 右侧（尾部）
        // 将 conversationId 传递给 toJson，用于在序列化 AssistantMessage 时关联 ToolResultHolder 中的 params
        messages.forEach(message -> listOps.rightPush(MessageUtil.toJson(message, conversationId)));

        // TODO 可优化：设置过期时间，自动清理历史会话
        // stringRedisTemplate.expire(redisKey, 7, TimeUnit.DAYS);
    }


    /**
     * 获取指定会话的最近 N 条消息
     * @param conversationId 会话 ID
     * @param lastN          需要获取的消息数量（如最近 10 轮 = 20 条消息）
     * @return 消息列表，按时间正序（最早在前，最新在后）
     */
    @Override
    public List<Message> get(String conversationId, int lastN) {
        // 验证参数有效性，当lastN非正数时直接返回空结果
        if (lastN <= 0) {
            return List.of();
        }
        // 生成Redis键名用于存储会话消息
        String redisKey = getKey(conversationId);
        // 获取Redis列表操作对象
        BoundListOperations<String, String> listOps = stringRedisTemplate.boundListOps(redisKey);

        // 从Redis列表中获取指定范围的元素（从第一个元素开始到lastN位置），框架默认是取100条，range相当于每次会获取最旧的聊天记忆
        List<String> messages = listOps.range(0, lastN);
        // 将Redis返回的字符串列表转换为Message对象列表
        return CollStreamUtil.toList(messages, MessageUtil::toMessage);
    }

    /**
     * 清除指定会话的所有记忆
     * 
     * 使用场景：
     * - 用户删除会话
     * - 会话过期清理
     * - 测试环境重置数据
     * 
     * @param conversationId 会话 ID
     */
    @Override
    public void clear(String conversationId) {
        String redisKey = getKey(conversationId);
        // DEL 命令删除整个 Key，时间复杂度 O(1)
        stringRedisTemplate.delete(redisKey);
    }

    /**
     * 生成 Redis Key
     * 
     * 格式：{prefix}{conversationId}
     * 示例：CHAT:1_abc123
     * 
     * @param conversationId 会话 ID
     * @return 完整的 Redis Key
     */
    private String getKey(String conversationId) {
        return prefix + conversationId;
    }
}