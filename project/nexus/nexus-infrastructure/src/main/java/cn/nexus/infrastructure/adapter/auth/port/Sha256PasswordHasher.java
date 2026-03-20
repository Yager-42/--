package cn.nexus.infrastructure.adapter.auth.port;

import cn.nexus.domain.auth.adapter.port.IPasswordHasher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * 默认 SHA-256 哈希实现。
 */
@Component
public class Sha256PasswordHasher implements IPasswordHasher {

    @Override
    public String hash(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new IllegalStateException("sha-256 hash failed", e);
        }
    }

    @Override
    public boolean matches(String raw, String hash) {
        if (raw == null || hash == null || hash.isBlank()) {
            return false;
        }
        return hash(raw).equals(hash);
    }
}
