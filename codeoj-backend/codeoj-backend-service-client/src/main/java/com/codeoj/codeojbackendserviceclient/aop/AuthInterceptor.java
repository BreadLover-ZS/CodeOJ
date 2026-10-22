package com.codeoj.codeojbackendserviceclient.aop;

import com.codeoj.codeojbackendcommon.annotation.AuthCheck;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.entity.User;
import com.codeoj.codeojbackendserviceclient.service.UserFeignClient;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.annotation.Resource;

/**
 * 登录与权限拦截器（基于 Spring Session Redis 共享登录态）
 *
 * <p>将登录用户存入 session 后，各服务通过同一个 Redis 会话反序列化出用户对象，
 * Session 仅提供账号 ID；切面通过用户服务查询现时角色，再校验 {@link AuthCheck}。
 */
@Aspect
@Component
public class AuthInterceptor {

    @Resource
    private UserFeignClient userFeignClient;

    /**
     * 校验登录态与所需角色
     *
     * @param joinPoint 被拦截的方法
     * @param authCheck 方法上的权限注解
     */
    @Around("@annotation(authCheck)")
    public Object doInterceptor(ProceedingJoinPoint joinPoint, AuthCheck authCheck) throws Throwable {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attributes != null ? attributes.getRequest() : null;
        User user = userFeignClient.getLoginUser(request);

        String mustRole = authCheck.mustRole();
        if (mustRole == null || mustRole.isEmpty()) {
            // 仅要求登录
            return joinPoint.proceed();
        }

        // 要求指定角色
        String userRole = user.getUserRole();
        if (!mustRole.equals(userRole)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        return joinPoint.proceed();
    }
}
