package com.tianji.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * @Name: AIProperties
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 17:11
 * @Description:
 */
@Configuration
@Data
@ConfigurationProperties(prefix = "tj.ai.prompt")
public class AIProperties {

    private System system;

    @Data
    public static class System{
        private Chat chat;

        @Data
        public static class Chat {
            private String dataId;
            private String group = "DEFAULT_GROUP";
            private long timeoutMs = 20000L; // 读取的超时时间，单位毫秒
        }
    }
}
