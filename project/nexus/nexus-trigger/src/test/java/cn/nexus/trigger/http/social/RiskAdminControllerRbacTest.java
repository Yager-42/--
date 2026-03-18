package cn.nexus.trigger.http.social;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.dev33.satoken.annotation.SaCheckRole;
import org.junit.jupiter.api.Test;

class RiskAdminControllerRbacTest {

    @Test
    void riskAdminController_shouldRequireAdminRole() {
        SaCheckRole annotation = RiskAdminController.class.getAnnotation(SaCheckRole.class);

        assertNotNull(annotation);
        assertArrayEquals(new String[]{"ADMIN"}, annotation.value());
    }
}
