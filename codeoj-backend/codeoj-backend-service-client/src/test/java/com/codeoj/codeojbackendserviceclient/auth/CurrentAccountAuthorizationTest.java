package com.codeoj.codeojbackendserviceclient.auth;

import com.codeoj.codeojbackendcommon.annotation.AuthCheck;
import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.entity.User;
import com.codeoj.codeojbackendserviceclient.aop.AuthInterceptor;
import com.codeoj.codeojbackendserviceclient.service.UserFeignClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static com.codeoj.codeojbackendcommon.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentAccountAuthorizationTest {
    private final UserFeignClient users = mock(UserFeignClient.class, CALLS_REAL_METHODS);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private static User account(String role) {
        User user = new User(); user.setId(7L); user.setUserRole(role); return user;
    }

    private void session(String role) { request.getSession().setAttribute(USER_LOGIN_STATE, account(role)); }

    private BusinessException denied(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        BusinessException exception = assertThrows(BusinessException.class, action);
        assertEquals(code.getCode(), exception.getCode());
        return exception;
    }

    private ProtectedOperations operations() {
        AuthInterceptor aspect = new AuthInterceptor();
        ReflectionTestUtils.setField(aspect, "userFeignClient", users);
        AspectJProxyFactory factory = new AspectJProxyFactory(new ProtectedOperations());
        factory.addAspect(aspect);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return factory.getProxy();
    }

    @AfterEach void clearRequest() { RequestContextHolder.resetRequestAttributes(); }

    @Test void sessionOnlySuppliesIdentityAndEveryRequestReadsCurrentAccount() {
        session("admin"); User current = account("user");
        when(users.getById(7L)).thenReturn(current, account("admin"));
        assertSame(current, users.getLoginUser(request));
        assertFalse(users.isAdmin(current));
        assertEquals("admin", users.getLoginUser(request).getUserRole());
        verify(users, times(2)).getById(7L);
    }

    @Test void absentMalformedAndIncompleteSessionsAreUnauthenticated() {
        denied(ErrorCode.NOT_LOGIN_ERROR, () -> users.getLoginUser(request));
        assertNull(request.getSession(false));
        request.getSession().setAttribute(USER_LOGIN_STATE, "invalid");
        denied(ErrorCode.NOT_LOGIN_ERROR, () -> users.getLoginUser(request));
        request.getSession().setAttribute(USER_LOGIN_STATE, new User());
        denied(ErrorCode.NOT_LOGIN_ERROR, () -> users.getLoginUser(request));
        denied(ErrorCode.NOT_LOGIN_ERROR, () -> users.getLoginUser(null));
        verify(users, never()).getById(anyLong());
    }

    @Test void removedOrDeletedAccountCannotReuseAnOldSession() {
        session("admin");
        when(users.getById(7L)).thenReturn(null);
        denied(ErrorCode.NOT_LOGIN_ERROR, () -> users.getLoginUser(request));
        User deleted = account("admin"); deleted.setIsDelete(1);
        when(users.getById(7L)).thenReturn(deleted);
        denied(ErrorCode.NOT_LOGIN_ERROR, () -> users.getLoginUser(request));
    }

    @Test void bannedAccountCannotUseAnOldOrdinarySession() {
        session("user"); when(users.getById(7L)).thenReturn(account("ban"));
        denied(ErrorCode.NO_AUTH_ERROR, () -> users.getLoginUser(request));
    }

    @Test void lookupFailureNeverFallsBackToCachedAdmin() {
        session("admin"); IllegalStateException unavailable = new IllegalStateException("user service unavailable");
        when(users.getById(7L)).thenThrow(unavailable);
        assertSame(unavailable, assertThrows(IllegalStateException.class, () -> users.getLoginUser(request)));
    }

    @Test void realAnnotationProxyRejectsDemotedAdminBeforeExecutingBody() {
        session("admin"); when(users.getById(7L)).thenReturn(account("user"));
        ProtectedOperations proxy = operations();
        denied(ErrorCode.NO_AUTH_ERROR, proxy::admin);
    }

    @Test void currentAdminPromotionTakesEffectWithoutRelogin() {
        session("user"); when(users.getById(7L)).thenReturn(account("admin"));
        assertEquals("executed", operations().admin());
    }

    @Test void loginOnlyAnnotationRejectsNewBanAndDeletedUser() {
        session("user"); ProtectedOperations proxy = operations();
        when(users.getById(7L)).thenReturn(account("ban"));
        denied(ErrorCode.NO_AUTH_ERROR, proxy::loggedIn);
        when(users.getById(7L)).thenReturn(null);
        denied(ErrorCode.NOT_LOGIN_ERROR, proxy::loggedIn);
    }

    @Test void annotationDoesNotProceedWhenLookupFails() {
        session("admin"); ProtectedOperations proxy = operations();
        when(users.getById(7L)).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, proxy::admin);
    }

    public static class ProtectedOperations {
        @AuthCheck(mustRole = "admin") public String admin() { return "executed"; }
        @AuthCheck public String loggedIn() { return "executed"; }
    }
}
