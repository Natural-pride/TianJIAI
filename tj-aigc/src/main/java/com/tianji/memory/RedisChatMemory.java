package com.tianji.memory;

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
        messages.forEach(message -> {
            // Message 对象 → 包装类 → JSON 字符串
            // 使用包装类保存 messageType，解决反序列化时类型丢失问题
            ChatMessageWrapper wrapper = ChatMessageWrapper.fromMessage(message);
            String jsonMessage = JSONUtil.toJsonStr(wrapper);
            // RPUSH 命令：添加到 List 尾部，时间复杂度 O(1)
            listOps.rightPush(jsonMessage);
        });

        // TODO 可优化：设置过期时间，自动清理历史会话
        // stringRedisTemplate.expire(redisKey, 7, TimeUnit.DAYS);
    }

    /**
     * 获取最近 lastN 条消息（核心方法）
     *
     * 用于构建多轮对话上下文：
     * - Spring AI 的 MessageChatMemoryAdvisor 会调用此方法
     * - 返回的消息将拼接到 system message 发送给 AI 模型
     * - AI 据此理解历史对话，实现上下文连贯的多轮对话
     *
     * 执行流程：
     * 1. 计算起始索引：-lastN（负数表示从尾部开始计数）
     * 2. 边界处理：lastN 为 0 时返回空列表
     * 3. Redis LRANGE：获取指定范围的消息（时间复杂度 O(N)）
     * 4. 反序列化：JSON 字符串 → 包装类 → Message 对象
     *
     * Redis 命令对应：
     * LRANGE CHAT:123 -10 -1  （获取最后 10 条）
     * LRANGE CHAT:123 0 -1     （获取全部）
     *
     * @param conversationId 会话 ID
     * @param lastN          需要获取的消息数量（如 20 表示最近 10 轮对话）
     * @return 消息列表，按时间正序（最早在前，最新在后）
     */
    @Override
    public List<Message> get(String conversationId, int lastN) {
        // 防御：lastN <= 0 时不获取任何消息
        if (lastN <= 0) {
            return List.of();
        }

        String redisKey = getKey(conversationId);

        // LRANGE key start stop
        // -lastN 表示从尾部往前数 lastN 个位置
        // -1 表示最后一个元素（最新）
        // 示例：List 有 100 条，lastN=10 → LRANGE key -10 -1 → 获取第 91-100 条
        List<String> jsonMessages = stringRedisTemplate.boundListOps(redisKey)
                .range(-lastN, -1);

        // 防御：Key 不存在时返回空列表
        if (CollUtil.isEmpty(jsonMessages)) {
            return List.of();
        }

        // JSON 反序列化：String → 包装类 → Message 对象
        // 使用包装类解决 Hutool 无法反序列化 Message 接口的问题
        return jsonMessages.stream()
                .map(json -> {
                    ChatMessageWrapper wrapper = JSONUtil.toBean(json, ChatMessageWrapper.class);
                    return wrapper.toMessage();
                })
                .collect(Collectors.toList());
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