package com.tianji.config;


import com.tianji.memory.RedisChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 核心配置类
 * 负责配置聊天客户端(ChatClient)及相关的顾问器(Advisor)和记忆存储(Memory)
 * 为整个AI聊天功能提供统一的客户端管理和记忆能力
 * 
 * @Name: SpringAIConfig
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 16:20
 * @Description: Spring AI配置类，用于创建ChatClient并配置日志记录和会话记忆功能
 */

@Configuration
public class SpringAIConfig {

    /**
     * 配置ChatClient，（Spring AI 的核心聊天客户端）
     * @param chatClientBuilder 聊天客户端构建器
     * @param loggerAdvisor 日志记录器顾问
     * @param messageChatMemoryAdvisor 聊天记忆顾问
     * @description 用途 ：作为整个 AI 聊天功能的入口点，统一管理日志记录和会话记忆
     * @return ChatClient
     */
    /**
     * 创建并配置AI聊天客户端(ChatClient)
     * 这是Spring AI的核心组件，用于与AI模型进行交互
     * 
     * @param chatClientBuilder 由Spring AI自动配置的构建器，包含基础配置（如API密钥、模型选择等）
     * @param loggerAdvisor 日志记录顾问器，用于记录请求和响应的详细信息
     * @param messageChatMemoryAdvisor 聊天记忆顾问器，实现多轮对话的上下文管理
     * @return 配置完成的ChatClient实例，供整个项目注入使用
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                 Advisor loggerAdvisor,
                                 Advisor messageChatMemoryAdvisor) {
        return chatClientBuilder
                .defaultAdvisors(loggerAdvisor, messageChatMemoryAdvisor)
                .build();
    }

    /**
     * 日志记录器
     * 用于记录所有AI聊天请求和响应的详细信息，包括请求内容、响应结果、耗时等
     * 在生产环境中排查AI异常行为、性能问题和用户反馈时非常重要
     */
    @Bean
    public Advisor loggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }

    /**
     * 创建自定义的 RedisChatMemory Bean
     * @description 用途 ：创建自定义的 RedisChatMemory 实现，用于存储和检索聊天会话记忆
     * @return ChatMemory
     */
    @Bean
    public ChatMemory chatMemory() {
        return new RedisChatMemory();
    }

    /**
     * 聊天记忆顾问器
     * 负责自动管理对话历史记录的存储和检索
     * 工作原理：
     * 1. 每次请求前：从ChatMemory获取历史对话记录
     * 2. 将历史对话整合到当前请求的system message中发送给AI模型
     * 3. 每次响应后：将当前轮次的对话保存到ChatMemory中
     * 
     * 这样AI模型就能理解上下文，实现真正的多轮对话能力
     * 例如：用户在上一轮提到"帮我查订单"，下一轮问"到哪了"时，AI能理解是指订单物流
     * 
     * @param chatMemory 聊天记忆存储实现（这里使用Redis实现分布式会话共享）
     */
    @Bean
    public Advisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return new MessageChatMemoryAdvisor(chatMemory);
    }
}
