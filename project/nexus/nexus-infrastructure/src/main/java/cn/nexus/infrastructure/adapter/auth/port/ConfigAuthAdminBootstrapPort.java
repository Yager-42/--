package cn.nexus.infrastructure.adapter.auth.port;

import cn.nexus.domain.auth.adapter.port.IAuthAdminBootstrapPort;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 基于配置的首批管理员引导策略。
 */
@Component
@ConfigurationProperties(prefix = "auth.admin")
public class ConfigAuthAdminBootstrapPort implements IAuthAdminBootstrapPort {

    private List<String> bootstrapPhones = new ArrayList<>();

    @Override
    public boolean shouldGrantAdmin(String phone) {
        String normalizedPhone = normalize(phone);
        if (normalizedPhone == null) {
            return false;
        }
        for (String bootstrapPhone : bootstrapPhones) {
            if (normalizedPhone.equals(normalize(bootstrapPhone))) {
                return true;
            }
        }
        return false;
    }

    public List<String> getBootstrapPhones() {
        return bootstrapPhones;
    }

    public void setBootstrapPhones(List<String> bootstrapPhones) {
        this.bootstrapPhones = bootstrapPhones == null ? new ArrayList<>() : bootstrapPhones;
    }

    private String normalize(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        return phone.trim();
    }
}
