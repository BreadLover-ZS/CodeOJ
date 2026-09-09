package com.codeoj.codeojbackendserviceclient.filter;

import com.codeoj.codeojbackendserviceclient.constant.InnerAuthConstant;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * 内部接口（/inner/**）鉴权过滤器
 *
 * <p>即使网关已拦截 inner 请求，攻击者若绕过网关直连服务端口，仍可能调用内部接口。
 * 该过滤器在各业务服务内部二次校验：内部接口必须携带正确且非空的 {@link InnerAuthConstant#X_INNER_SECRET} 请求头，
 * 否则一律拒绝，实现"双层防护"。
 */
@Slf4j
@Component
public class InnerApiAuthFilter extends OncePerRequestFilter {

    @Value("${codeoj.inner-auth-secret:codeoj-default-inner-secret}")
    private String innerAuthSecret;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        // 仅对内部接口做校验，普通业务接口放行（由 @AuthCheck 等鉴权处理）
        if (uri != null && uri.contains("/inner/")) {
            String secret = request.getHeader(InnerAuthConstant.X_INNER_SECRET);
            if (!StringUtils.hasText(secret) || !innerAuthSecret.equals(secret)) {
                log.warn("内部接口鉴权失败，来源: {}", request.getRemoteAddr());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.getWriter().write("无权限");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}