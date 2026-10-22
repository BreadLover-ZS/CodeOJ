package com.codeoj.codeojbackendserviceclient.auth;

import com.codeoj.codeojbackendcommon.common.ErrorCode;
import com.codeoj.codeojbackendcommon.exception.BusinessException;
import com.codeoj.codeojbackendmodel.model.entity.User;
import com.codeoj.codeojbackendmodel.model.enums.UserRoleEnum;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.function.Function;

import static com.codeoj.codeojbackendcommon.constant.UserConstant.USER_LOGIN_STATE;

/** Session supplies identity only; authorization always uses the current account. */
public final class SessionUserResolver {
    private SessionUserResolver() { }

    public static User requireLogin(HttpServletRequest request, Function<Long, User> lookup) {
        User user = permitNull(request, lookup);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return user;
    }

    public static User permitNull(HttpServletRequest request, Function<Long, User> lookup) {
        HttpSession session = request == null ? null : request.getSession(false);
        Object value = session == null ? null : session.getAttribute(USER_LOGIN_STATE);
        if (!(value instanceof User)) {
            return null;
        }
        Long userId = ((User) value).getId();
        if (userId == null || userId <= 0) {
            return null;
        }
        // Propagate lookup failures. Never fall back to the serialized Session role.
        User current = lookup.apply(userId);
        if (current == null || !userId.equals(current.getId()) || Integer.valueOf(1).equals(current.getIsDelete())) {
            return null;
        }
        if (UserRoleEnum.BAN.getValue().equals(current.getUserRole())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "账号已被封禁");
        }
        return current;
    }
}
