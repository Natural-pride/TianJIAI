package com.tianji.common.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 禁用统一响应包装的注解
 * 标注在 Controller 方法上，表示该方法返回值不需要被 WrapperResponseBodyAdvice 包装
 * 
 * 使用场景：
 * - SSE 流式响应（返回 Flux）：SSE 协议要求纯文本格式，不能被 JSON 包装
 * - 二进制文件下载：返回 byte[] 或 Resource，包装后无法正常下载
 * - 第三方回调接口：需要保持原始响应格式
 * 
 * 工作机制：
 * WrapperResponseBodyAdvice 检测到方法标注 @NoWrapper 后，直接返回原始返回值
 * 
 * @Author: Natural Pride
 * @CreateTime: 2026/7/3
 * @Description: 绕过统一响应包装的标记注解
 */
@Retention(RetentionPolicy.RUNTIME)  // 运行时保留，便于 AOP 读取
@Target(ElementType.METHOD)          // 仅适用于方法级别
public @interface NoWrapper {

}
