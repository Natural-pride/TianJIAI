package com.tianji.config;

import com.tianji.vo.SessionVO;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "tj.ai.session")
public class SessionProperties {

    /**
     * AI助手的标题，用于显示助手的名称或身份。
     */
    private String title = "AI智能助手";

    /**
     * AI助手的描述，简要介绍助手的功能或特点。
     */
    private String describe = "您好，我是您的智能学习助手，可以回答课程、学习、职业规划等各类问题";

    /**
     * 示例列表，包含一些使用助手的示例。
     */
    private List<SessionVO.Example> examples = new ArrayList<>();

}