package com.codeoj.codeojbackendserviceclient.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;

/** Session-store failures can occur before MVC advice or while the session filter commits.
 * Preserve the original session identity and report unavailability, never false logout/not-found. */
@Component
@Slf4j
@Order(SessionRepositoryFilter.DEFAULT_ORDER-1)
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class SessionAvailabilityFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        try { chain.doFilter(request,response); }
        catch(RuntimeException | ServletException failure) {
            if(!redisFailure(failure) || response.isCommitted()) { throw failure; }
            // No exception messages, cookie values, driver data or request bodies in the diagnostic.
            log.warn("Redis-dependent request unavailable: type={}",failure.getClass().getName());
            response.reset();response.setStatus(503);response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control","no-store");
            response.getOutputStream().write("{\"code\":50010,\"data\":null,\"message\":\"SESSION_STORE_UNAVAILABLE\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private boolean redisFailure(Throwable failure) {
        for(int depth=0;failure!=null && depth<8;depth++,failure=failure.getCause()) {
            String name=failure.getClass().getName();
            if(name.startsWith("org.springframework.data.redis.") || name.startsWith("io.lettuce.core.")) { return true; }
        }
        return false;
    }
}
