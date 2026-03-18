package cn.nexus.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AuthApiContractTest {

    @Test
    void authApi_shouldExposeSmsSendRegisterPasswordLoginSmsLoginChangePasswordLogoutAndMe() {
        List<String> methodNames = Arrays.stream(IAuthApi.class.getDeclaredMethods())
                .map(Method::getName)
                .sorted()
                .collect(Collectors.toList());

        assertEquals(List.of(
                "changePassword",
                "logout",
                "me",
                "passwordLogin",
                "register",
                "sendSms",
                "smsLogin"
        ), methodNames);
        assertFalse(methodNames.contains("login"));
        assertTrue(methodNames.contains("sendSms"));
        assertTrue(methodNames.contains("register"));
        assertTrue(methodNames.contains("passwordLogin"));
        assertTrue(methodNames.contains("smsLogin"));
        assertTrue(methodNames.contains("changePassword"));
        assertTrue(methodNames.contains("logout"));
        assertTrue(methodNames.contains("me"));
    }
}
