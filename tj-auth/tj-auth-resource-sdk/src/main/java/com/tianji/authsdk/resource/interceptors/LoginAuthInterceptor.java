package com.tianji.authsdk.resource.interceptors;

import com.tianji.common.exceptions.UnauthorizedException;
import com.tianji.common.utils.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;


@Slf4j
public class LoginAuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response, Object handler) throws Exception {
        // 1.尝试获取用户信息
        Long userId = UserContext.getUser();
        // 2.判断是否登录
        if (userId == null) {
            // 记录日志，方便排查：哪个路径被拦截、客户端 IP、请求方法
            log.warn("未登录访问受限资源 -> uri: {}, method: {}, remoteAddr: {}",
                    request.getRequestURI(), request.getMethod(), request.getRemoteAddr());
            // 抛出业务异常，由 CommonExceptionAdvice 统一处理并返回标准 JSON 响应
            throw new UnauthorizedException("未登录用户无法访问！");
        }
        // 3.登录则放行
        return true;
    }

}
