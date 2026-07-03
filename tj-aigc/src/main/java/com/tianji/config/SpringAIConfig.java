package com.tianji.config;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @Name: SpringAIConfig
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 16:20
 * @Description:
 */

@Configuration
public class SpringAIConfig {
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder, Advisor loggerAdvisor) {
        return chatClientBuilder
                .defaultAdvisors(loggerAdvisor)
                .build();
    }

    /**
     * 日志记录器
     */
    @Bean
    public Advisor loggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }
}
