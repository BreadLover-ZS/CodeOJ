package com.codeoj.codeojbackendserviceclient.aop;

import com.codeoj.codeojbackendcommon.annotation.AuthCheck;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.constant.UserConstant;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.entity.User;
import com.codeoj.codeojbackendmodel.model.enums.UserRoleEnum;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;

/**
 * 登录与权限拦截器（基于 Spring Session Redis 共享登录态）
 *
 * <p>将登录用户存入 session 后，各服务通过同一个 Redis 会话反序列化出用户对象，
 * 这里的切面即根据 session 中的用户角色对标注了 {@link AuthCheck} 的接口做统一鉴权。
 */
@Aspect
@Component
public class AuthInterceptor {

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
        Object userObj = request == null ? null : request.getSession().getAttribute(UserConstant.USER_LOGIN_STATE);
        User user = userObj instanceof User ? (User) userObj : null;

        String mustRole = authCheck.mustRole();
        if (mustRole == null || mustRole.isEmpty()) {
            // 仅要求登录
            if (user == null) {
                throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
            }
            // 封号用户拒绝访问所有业务接口（登录/查询登录用户等无 @AuthCheck 的接口不受影响）
            if (UserRoleEnum.BAN.getValue().equals(user.getUserRole())) {
                throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "账号已被封禁");
            }
            return joinPoint.proceed();
        }

        // 要求指定角色
        String userRole = user == null ? null : user.getUserRole();
        if (UserRoleEnum.BAN.getValue().equals(userRole)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "账号已被封禁");
        }
        if (!mustRole.equals(userRole)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        return joinPoint.proceed();
    }
}