package cn.nexus.trigger.http.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.nexus.types.exception.AppException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class UserContextTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void requireUserId_shouldReturnCurrentUser() {
        UserContext.setUserId(12L);

        assertEquals(12L, UserContext.getUserId());
        assertEquals(12L, UserContext.requireUserId());
    }

    @Test
    void requireUserId_shouldThrowWhenMissing() {
        assertThrows(AppException.class, UserContext::requireUserId);
    }

    @Test
    void clear_shouldRemoveCurrentUser() {
        UserContext.setUserId(99L);

        UserContext.clear();

        assertNull(UserContext.getUserId());
    }
}
