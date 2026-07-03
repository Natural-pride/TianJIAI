package com.tianji.config;


import com.alibaba.cloud.nacos.NacosConfigManager;
import com.alibaba.nacos.api.config.listener.Listener;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @Name: SystemPromptConfig
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 17:14
 * @Description:
 */


@Slf4j
@Getter
@Configuration
@RequiredArgsConstructor
public class SystemPromptConfig {

    private final NacosConfigManager nacosConfigManager;

    private final AIProperties aiProperties;

    // 使用原子引用，保证线程安全
    private final AtomicReference<String> chatSystemMessage = new AtomicReference<>();

    @PostConstruct // 初始化时加载配置
    public void init() {
        // 读取配置文件
        loadConfig(aiProperties.getSystem().getChat(),chatSystemMessage);
    }

    /**
     * 从 Nacos 配置中心加载指定配置项的内容到传入的原子引用中，并注册配置变更监听器，
     * 使得后续配置中心推送变更时能够自动同步到本地引用，实现"配置热更新"的效果。
     *
     * @param chatConfig 配置项元信息，包含 dataId（配置 ID）、group（分组）、timeoutMs（拉取超时时间）
     * @param target 用于承载配置内容的原子引用；初次加载与后续热更新结果都会写入该引用，
     *               保证多线程环境下读取配置内容的可见性与原子性
     */
    private void loadConfig(AIProperties.System.Chat chatConfig, AtomicReference<String> target) {
        try {
            // 解析本次拉取所需的 Nacos 配置标识与超时时间
            String dataId = chatConfig.getDataId();
            String group = chatConfig.getGroup();
            long timeoutMs = chatConfig.getTimeoutMs();

            // 同步从 Nacos 拉取一次配置内容，作为初始值写入 target
            String config = nacosConfigManager.getConfigService().getConfig(dataId, group, timeoutMs);
            target.set(config);
            log.info("读取{}成功，内容为：{}", target, config);

            // 注册 Nacos 配置变更监听器，配置中心推送变更时自动刷新 target，实现热更新
            nacosConfigManager.getConfigService().addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    return null;
                }

                @Override
                public void receiveConfigInfo(String info) {
                    target.set(info);
                    log.info("更新{}成功，内容为：{}", target, info);
                }
            });
        } catch (Exception e) {
            // 任何异常都仅记录日志，不向上抛出，保证应用启动 / 业务流程不受配置拉取失败影响
            log.error("加载配置失败", e);
        }
    }

}
