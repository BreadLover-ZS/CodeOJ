package com.codeoj.codeojbackenduserservice.service;

import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.entity.User;
import com.codeoj.codeojbackenduserservice.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static com.codeoj.codeojbackendcommon.constant.UserConstant.USER_LOGIN_STATE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CurrentUserServiceTest {
    private final UserServiceImpl service = spy(new UserServiceImpl());
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private User user(String role) {
        User user = new User(); user.setId(8L); user.setUserRole(role); return user;
    }

    @Test void localAdminCheckRefreshesRoleWithoutRelogin() {
        request.getSession().setAttribute(USER_LOGIN_STATE, user("admin"));
        doReturn(user("user")).when(service).getById(8L);
        assertFalse(service.isAdmin(request));
        doReturn(user("admin")).when(service).getById(8L);
        assertTrue(service.isAdmin(request));
    }

    @Test void allLocalLoginHelpersRejectCurrentBan() {
        request.getSession().setAttribute(USER_LOGIN_STATE, user("user"));
        doReturn(user("ban")).when(service).getById(8L);
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), assertThrows(BusinessException.class,
                () -> service.getLoginUser(request)).getCode());
        assertThrows(BusinessException.class, () -> service.getLoginUserPermitNull(request));
        assertThrows(BusinessException.class, () -> service.isAdmin(request));
    }

    @Test void anonymousOptionalLookupDoesNotCreateASession() {
        assertNull(service.getLoginUserPermitNull(request));
        assertFalse(service.isAdmin(request));
        assertNull(request.getSession(false));
        verify(service, never()).getById(anyLong());
    }

    @Test void deletedAccountIsUnauthenticatedEvenWithOldSession() {
        request.getSession().setAttribute(USER_LOGIN_STATE, user("admin"));
        doReturn(null).when(service).getById(8L);
        assertNull(service.getLoginUserPermitNull(request));
        assertFalse(service.isAdmin(request));
        assertEquals(ErrorCode.NOT_LOGIN_ERROR.getCode(), assertThrows(BusinessException.class,
                () -> service.getLoginUser(request)).getCode());
    }

    @Test void databaseFailureDoesNotGrantCachedRole() {
        request.getSession().setAttribute(USER_LOGIN_STATE, user("admin"));
        doThrow(new IllegalStateException("database unavailable")).when(service).getById(8L);
        assertThrows(IllegalStateException.class, () -> service.isAdmin(request));
    }
}
