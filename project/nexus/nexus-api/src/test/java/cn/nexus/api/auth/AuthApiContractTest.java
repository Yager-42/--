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
    void authApi_shouldExposeExpectedEndpoints() {
        List<String> methodNames = Arrays.stream(IAuthApi.class.getDeclaredMethods())
                .map(Method::getName)
                .sorted()
                .collect(Collectors.toList());

        assertEquals(List.of(
                "changePassword",
                "grantAdmin",
                "listAdmins",
                "logout",
                "me",
                "passwordLogin",
                "refresh",
                "register",
                "revokeAdmin"
        ), methodNames);
        assertFalse(methodNames.contains("login"));
        assertTrue(methodNames.contains("register"));
        assertTrue(methodNames.contains("passwordLogin"));
        assertTrue(methodNames.contains("refresh"));
        assertTrue(methodNames.contains("changePassword"));
        assertTrue(methodNames.contains("grantAdmin"));
        assertTrue(methodNames.contains("revokeAdmin"));
        assertTrue(methodNames.contains("listAdmins"));
        assertTrue(methodNames.contains("logout"));
        assertTrue(methodNames.contains("me"));
    }
}
