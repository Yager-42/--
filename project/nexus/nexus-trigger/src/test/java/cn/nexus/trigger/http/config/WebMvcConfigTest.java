package cn.nexus.trigger.http.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.nexus.trigger.http.support.UserContextInterceptor;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

class WebMvcConfigTest {

    @Test
    void refreshRegisterAndPasswordLogin_shouldBeExcludedFromUserContextInterceptor() throws Exception {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(TestMvcConfig.class);
            context.refresh();

            RequestMappingHandlerMapping handlerMapping = context.getBean(RequestMappingHandlerMapping.class);

            assertNoHandlerChain(handlerMapping, "/api/v1/auth/register");
            assertNoHandlerChain(handlerMapping, "/api/v1/auth/login/password");
            assertNoHandlerChain(handlerMapping, "/api/v1/auth/refresh");
        }
    }

    private void assertNoHandlerChain(RequestMappingHandlerMapping handlerMapping, String path) throws Exception {
        HandlerExecutionChain chain = handlerMapping.getHandler(TestRequestFactory.get(path));
        assertNotNull(chain);
        List<?> interceptors = Arrays.asList(chain.getInterceptors());
        boolean hasUserContextInterceptor = interceptors.stream().anyMatch(UserContextInterceptor.class::isInstance);
        org.junit.jupiter.api.Assertions.assertFalse(hasUserContextInterceptor, () -> path + " should bypass UserContextInterceptor");
    }

    @EnableWebMvc
    static class TestMvcConfig implements WebMvcConfigurer {
        @org.springframework.context.annotation.Bean
        UserContextInterceptor userContextInterceptor() {
            return new UserContextInterceptor(new com.fasterxml.jackson.databind.ObjectMapper());
        }

        @org.springframework.context.annotation.Bean
        WebMvcConfig webMvcConfig(UserContextInterceptor interceptor) {
            return new WebMvcConfig(interceptor);
        }

        @org.springframework.context.annotation.Bean
        AuthTestController authTestController() {
            return new AuthTestController();
        }
    }

    @org.springframework.web.bind.annotation.RestController
    static class AuthTestController {
        @org.springframework.web.bind.annotation.PostMapping("/api/v1/auth/register")
        void register() {
        }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/auth/login/password")
        void passwordLogin() {
        }

        @org.springframework.web.bind.annotation.PostMapping("/api/v1/auth/refresh")
        void refresh() {
        }
    }

    static final class TestRequestFactory {
        private TestRequestFactory() {
        }

        static org.springframework.mock.web.MockHttpServletRequest get(String path) {
            org.springframework.mock.web.MockHttpServletRequest request = new org.springframework.mock.web.MockHttpServletRequest();
            request.setMethod("POST");
            request.setRequestURI(path);
            return request;
        }
    }
}
