package com.codeoj.codeojbackendserviceclient.filter;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.*;
import javax.servlet.ServletException;
import static org.junit.jupiter.api.Assertions.*;

class SessionAvailabilityFilterTest {
    @Test void unavailableStoreReturns503WithoutCookieDeletionOrSensitivePayload() throws Exception {
        MockHttpServletResponse response=new MockHttpServletResponse();
        new SessionAvailabilityFilter().doFilter(new MockHttpServletRequest(),response,(request,res)->{throw new RedisConnectionFailureException("PRIVATE_PASSWORD_AND_COOKIE");});
        assertEquals(503,response.getStatus());assertTrue(response.getContentAsString().contains("SESSION_STORE_UNAVAILABLE"));assertFalse(response.getContentAsString().contains("PRIVATE_"));assertNull(response.getHeader("Set-Cookie"));
    }
    @Test void servletWrappedRedisTimeoutIsAlsoUnavailable() throws Exception {
        MockHttpServletResponse response=new MockHttpServletResponse();
        new SessionAvailabilityFilter().doFilter(new MockHttpServletRequest(),response,(request,res)->{throw new ServletException(new io.lettuce.core.RedisCommandTimeoutException("PRIVATE_COMMAND"));});
        assertEquals(503,response.getStatus());assertFalse(response.getContentAsString().contains("PRIVATE_COMMAND"));
    }
    @Test void unrelatedApplicationFailureKeepsItsOriginalMeaning() {
        IllegalStateException error=new IllegalStateException("NOT_A_REDIS_FAILURE");
        assertSame(error,assertThrows(IllegalStateException.class,()->new SessionAvailabilityFilter().doFilter(new MockHttpServletRequest(),new MockHttpServletResponse(),(request,response)->{throw error;})));
    }
    @Test void committedResponseCannotBeRewrittenAsSuccessOrNewJson() {
        MockHttpServletResponse response=new MockHttpServletResponse();response.setCommitted(true);RedisConnectionFailureException error=new RedisConnectionFailureException("unavailable");
        assertSame(error,assertThrows(RedisConnectionFailureException.class,()->new SessionAvailabilityFilter().doFilter(new MockHttpServletRequest(),response,(request,res)->{throw error;})));assertEquals(0,response.getContentAsByteArray().length);
    }
}
