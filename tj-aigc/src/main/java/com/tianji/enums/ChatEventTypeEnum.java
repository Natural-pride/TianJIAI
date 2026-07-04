package com.tianji.enums;

import com.tianji.common.enums.BaseEnum;
import lombok.Getter;

/**
 * 聊天事件类型枚举
 * 定义流式聊天中所有可能的事件类型，用于前端区分处理不同事件
 * 
 * 事件使用说明：
 * - DATA (1001)：正常数据推送，前端将 eventData 追加到聊天框
 * - STOP (1002)：流终止信号，前端关闭 SSE 连接，显示"生成完成"
 * - PARAM (1003)：预留参数事件，未来可用于动态调整 AI 参数（如温度、topP 等）
 * 
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3
 * @Description: 流式聊天事件类型枚举
 */
@Getter
public enum ChatEventTypeEnum implements BaseEnum {

    /**
     * 数据事件：携带 AI 回复的文本片段
     * 前端处理：将 eventData 内容追加到对话展示区域
     */
    DATA(1001, "数据事件"),

    /**
     * 停止事件：通知前端本轮对话已结束
     * 前端处理：关闭 SSE 连接，更新 UI 状态为"生成完成"
     */
    STOP(1002, "停止事件"),

    /**
     * 参数事件（预留）：用于动态调整 AI 行为参数
     * 前端展示示例：显示"正在调整回复风格为正式"
     */
    PARAM(1003, "参数事件");

    private final int value;
    private final String desc;

    ChatEventTypeEnum(int value, String desc) {
        this.value = value;
        this.desc = desc;
    }

    @Override
    public String toString() {
        return this.name();
    }
    
}