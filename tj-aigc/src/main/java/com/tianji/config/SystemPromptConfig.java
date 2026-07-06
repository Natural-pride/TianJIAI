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
 * 系统提示词配置类
 * @Name: SystemPromptConfig
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3 17:14
 * @Description: 系统提示词动态配置类，从 Nacos 加载并支持热更新
 * 核心职责：
 * 1. 从 Nacos 配置中心动态加载 AI 聊天系统的系统提示词（System Prompt）
 * 2. 注册配置变更监听器，实现配置的热更新（无需重启服务即可生效）
 * 3. 使用 AtomicReference 保证多线程环境下配置读取的可见性和原子性

 */


@Slf4j
@Getter
@Configuration
@RequiredArgsConstructor
public class SystemPromptConfig {

    // Nacos 配置管理器
    private final NacosConfigManager nacosConfigManager;

    // AI 相关的属性配置
    private final AIProperties aiProperties;

    /**
     * 聊天系统提示词的原子引用
     * 使用 AtomicReference 而非普通 String 的原因：
     * 1. 保证多线程环境下读取配置内容的可见性（volatile 语义）
     * 2. 保证配置更新的原子性（compareAndSet 语义）
     * 3. 主线程读取配置时，监听线程可能正在更新，避免读到半写状态
     */
    private final AtomicReference<String> chatSystemMessage = new AtomicReference<>();

    /**
     * 应用初始化方法，在 Bean 注入完成后自动执行
     * 职责：从 Nacos 配置中心拉取初始的系统提示词，并注册变更监听器
     */
    @PostConstruct
    public void init() {
        // 读取配置文件并注册监听器
        loadConfig(aiProperties.getSystem().getChat(), chatSystemMessage);
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
            // 1. 解析本次拉取所需的 Nacos 配置标识与超时时间
            String dataId = chatConfig.getDataId();   // 配置ID，唯一标识一个配置项
            String group = chatConfig.getGroup();      // 配置分组，用于隔离不同环境/业务
            long timeoutMs = chatConfig.getTimeoutMs(); // 拉取超时时间，防止网络阻塞

            // 2. 同步从 Nacos 拉取一次配置内容，作为初始值写入 target
            String config = nacosConfigManager.getConfigService().getConfig(dataId, group, timeoutMs);
            target.set(config);
            log.info("读取{}成功，内容为：{}", target, config);

            // 3. 注册 Nacos 配置变更监听器，配置中心推送变更时自动刷新 target，实现热更新
            nacosConfigManager.getConfigService().addListener(dataId, group, new Listener() {
                @Override
                public Executor getExecutor() {
                    // 返回 null 表示使用 Nacos 默认的线程池处理配置变更
                    return null;
                }

                @Override
                public void receiveConfigInfo(String info) {
                    // 配置中心推送新配置时触发此方法，自动更新本地引用
                    target.set(info);
                    log.info("更新{}成功，内容为：{}", target, info);
                }
            });
        } catch (Exception e) {
            // 任何异常都仅记录日志，不向上抛出，保证应用启动 / 业务流程不受配置拉取失败影响
            // 即使配置拉取失败，应用也能正常启动（使用空配置或默认配置）
            log.error("加载配置失败", e);
        }
    }

}
