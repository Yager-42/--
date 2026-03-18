package cn.nexus.trigger.http.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UserContextInterceptorTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void preHandle_shouldAllowOptionsRequest() throws Exception {
        UserContext.setUserId(999L);
        UserContextInterceptor interceptor = new UserContextInterceptor(new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod("OPTIONS");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertNull(UserContext.getUserId());
    }

    @Test
    void preHandle_shouldPreferTokenUserId() throws Exception {
        UserContextInterceptor interceptor = new UserContextInterceptor(new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("userId", "33");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stpUtil = Mockito.mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenReturn(22L);

            boolean allowed = interceptor.preHandle(request, response, new Object());

            assertTrue(allowed);
            assertEquals(22L, UserContext.requireUserId());
        }
    }

    @Test
    void preHandle_shouldRejectForgedUserIdHeaderWithoutToken() throws Exception {
        UserContextInterceptor interceptor = new UserContextInterceptor(new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("userId", "44");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stpUtil = Mockito.mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenThrow(new RuntimeException("no login"));

            boolean allowed = interceptor.preHandle(request, response, new Object());

            assertFalse(allowed);
            assertTrue(response.getContentAsString().contains("\"code\":\"0002\""));
            assertNull(UserContext.getUserId());
        }
    }

    @Test
    void preHandle_shouldRejectForgedXUserIdHeaderWithoutToken() throws Exception {
        UserContextInterceptor interceptor = new UserContextInterceptor(new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "55");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stpUtil = Mockito.mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenThrow(new RuntimeException("no login"));

            boolean allowed = interceptor.preHandle(request, response, new Object());

            assertFalse(allowed);
            assertTrue(response.getContentAsString().contains("\"code\":\"0002\""));
            assertNull(UserContext.getUserId());
        }
    }

    @Test
    void preHandle_shouldWriteIllegalParameterWhenTokenMissing() throws Exception {
        UserContextInterceptor interceptor = new UserContextInterceptor(new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        try (MockedStatic<StpUtil> stpUtil = Mockito.mockStatic(StpUtil.class)) {
            stpUtil.when(StpUtil::getLoginIdAsLong).thenThrow(new RuntimeException("no login"));

            boolean allowed = interceptor.preHandle(request, response, new Object());

            assertFalse(allowed);
            assertTrue(response.getContentAsString().contains("\"code\":\"0002\""));
            assertNull(UserContext.getUserId());
        }
    }

    @Test
    void afterCompletion_shouldClearThreadLocal() {
        UserContextInterceptor interceptor = new UserContextInterceptor(new ObjectMapper());
        UserContext.setUserId(66L);

        interceptor.afterCompletion(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), null);

        assertNull(UserContext.getUserId());
    }
}
