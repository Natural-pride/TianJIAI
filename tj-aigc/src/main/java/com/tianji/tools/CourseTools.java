package com.tianji.tools;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;

import com.tianji.api.client.course.CourseClient;
import com.tianji.config.ToolResultHolder;
import com.tianji.constants.Constant;
import com.tianji.tools.result.CourseInfo;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CourseTools {

    // 课程服务客户端
    private final CourseClient courseClient;

    // 字段名格式字符串常量
    private static final String FIELD_NAME_FORMAT = "{}_{}";  // 提取格式字符串常量

    /**
     * 根据课程id查询课程信息，并将结果缓存到工具调用结果容器中，
     * 以便同一请求链路上的后续工具调用可以按字段名引用该结果。
     *
     * @param courseId    课程id；若为 null 则返回 null
     * @param toolContext 工具调用上下文，从中获取请求id作为结果缓存的key
     * @return 课程信息 {@link CourseInfo}；当 courseId 为 null 时返回 null
     */
    @Tool(description = Constant.Tools.QUERY_COURSE_BY_ID)
    public CourseInfo queryCourseById(@ToolParam(description = Constant.ToolParams.COURSE_ID) Long courseId, ToolContext toolContext) {
        return Optional.ofNullable(courseId)
                .map(id -> CourseInfo.of(courseClient.baseInfo(id, true)))
                // 将查询到的课程信息按 requestId+field 维度缓存到工具调用结果容器，供后续工具复用
                .map(courseInfo -> {
                    // 存储数据的字段名
                    String field = StrUtil.format(FIELD_NAME_FORMAT,
                            StrUtil.lowerFirst(CourseInfo.class.getSimpleName()),
                            courseInfo.getId());
                    // 存储的key
                    String requestId = Convert.toStr(toolContext.getContext().get(Constant.REQUEST_ID));
                    ToolResultHolder.put(requestId, field, courseInfo);
                    return courseInfo;
                })
                .orElse(null);
    }
}