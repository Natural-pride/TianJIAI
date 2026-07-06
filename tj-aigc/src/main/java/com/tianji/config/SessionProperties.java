package com.tianji.config;

import com.tianji.vo.SessionVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * @Name:SessionProperties.java
 * @Author: Natural Pride
 * @CreateTime: 2026/7/6 20:42
 * @Description:
 * 从 application.yml 或 Nacos 读取会话相关配置
 * 配置和代码分离：修改AI助手标题/热门话题不需要改代码
 */

@Data
@Configuration
@ConfigurationProperties(prefix = "tj.ai.session")
public class SessionProperties {

    /**
     * AI助手的标题，用于显示助手的名称或身份。
     */
    private String title;

    /**
     * AI助手的描述，简要介绍助手的功能或特点。
     */
    private String describe;

    /**
     * 示例列表，包含一些使用助手的示例。
     */
    private List<SessionVO.Example> examples = new ArrayList<>();

}