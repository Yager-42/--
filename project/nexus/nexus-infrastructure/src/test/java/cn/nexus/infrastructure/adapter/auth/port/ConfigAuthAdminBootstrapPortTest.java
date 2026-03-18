package cn.nexus.infrastructure.adapter.auth.port;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigAuthAdminBootstrapPortTest {

    @Test
    void shouldGrantAdmin_shouldMatchTrimmedPhone() {
        ConfigAuthAdminBootstrapPort port = new ConfigAuthAdminBootstrapPort();
        port.setBootstrapPhones(List.of(" 13800138000 ", "13800138001"));

        assertTrue(port.shouldGrantAdmin("13800138000"));
        assertTrue(port.shouldGrantAdmin("13800138001"));
    }

    @Test
    void shouldGrantAdmin_whenPhoneNotConfigured_shouldReturnFalse() {
        ConfigAuthAdminBootstrapPort port = new ConfigAuthAdminBootstrapPort();
        port.setBootstrapPhones(List.of("13800138000"));

        assertFalse(port.shouldGrantAdmin("13800138002"));
        assertFalse(port.shouldGrantAdmin(" "));
    }
}
