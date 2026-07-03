package com.tianji.gateway.filter;

import cn.hutool.core.util.StrUtil;
import com.tianji.authsdk.gateway.util.AuthUtil;
import com.tianji.common.domain.R;
import com.tianji.common.domain.dto.LoginUserDTO;
import com.tianji.gateway.config.AuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.tianji.auth.common.constants.JwtConstants.*;

/**
 * 网关登录鉴权过滤器。
 * 兼容多种 token 请求头，避免因客户端传参头不规范（部分测试工具如 Apifox 默认用 token 头）
 * 导致下游服务拿不到 user-info，从而误判为"未登录"。
 */
@Slf4j
@Component
public class AccountAuthFilter implements GlobalFilter, Ordered {

    private static final String LEGACY_TOKEN_HEADER = "token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthUtil authUtil;
    private final AuthProperties authProperties;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public AccountAuthFilter(AuthUtil authUtil, AuthProperties authProperties) {
        this.authUtil = authUtil;
        this.authProperties = authProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1.获取请求request信息
        ServerHttpRequest request = exchange.getRequest();
        // String method = request.getMethodValue();
        String method = request.getMethod().name();
        String path = request.getPath().toString();
        String antPath = method + ":" + path;

        // 2.判断是否是无需登录的路径
        if(isExcludePath(antPath)){
            // 直接放行
            return chain.filter(exchange);
        }

        // 3.尝试获取用户信息（兼容 Authorization 和 token 两种请求头）
        String token = extractToken(request.getHeaders());
        R<LoginUserDTO> r = authUtil.parseToken(token);

        // 4.如果用户是登录状态，尝试更新请求头，传递用户信息
        if (r.success()) {
            // 修复点：mutate() 必须重新赋值给 exchange，否则下游拿不到新增 header
            exchange = exchange.mutate()
                    .request(builder -> builder
                            .header(USER_HEADER, r.getData().getUserId().toString())
                            .header(TOKEN_HEADER, token)
                    )
                    .build();
        } else {
            // 记录 token 解析失败原因，方便排查是缺少 token / token 无效 / token 过期
            log.warn("token 解析失败 -> method: {}, path: {}, code: {}, msg: {}",
                    method, path, r.getCode(), r.getMsg());
        }

        // 5.校验权限
        authUtil.checkAuth(antPath, r);

        // 6.放行
        return chain.filter(exchange);
    }

    private boolean isExcludePath(String antPath) {
        for (String pathPattern : authProperties.getExcludePath()) {
            if(antPathMatcher.match(pathPattern, antPath)){
                return true;
            }
        }
        return false;
    }

    /**
     * 提取请求中的 token。
     * 优先级：1）Authorization 头（自动去掉 Bearer 前缀）  2）兼容老式 token 头
     */
    private String extractToken(HttpHeaders headers) {
        String authHeader = headers.getFirst(AUTHORIZATION_HEADER);
        if (StrUtil.isNotBlank(authHeader)) {
            String value = authHeader.trim();
            if (value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
                value = value.substring(BEARER_PREFIX.length()).trim();
            }
            return value;
        }
        List<String> legacyHeaders = headers.get(LEGACY_TOKEN_HEADER);
        if (legacyHeaders != null && !legacyHeaders.isEmpty()) {
            return StrUtil.blankToDefault(legacyHeaders.get(0), "");
        }
        return "";
    }

    @Override
    public int getOrder() {
        return 1000;
    }
}
