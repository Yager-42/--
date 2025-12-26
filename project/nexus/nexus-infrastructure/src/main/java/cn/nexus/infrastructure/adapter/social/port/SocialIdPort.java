package cn.nexus.infrastructure.adapter.social.port;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 简单的ID与时间提供器，后续可替换为雪花算法。
 */
@Component
public class SocialIdPort implements ISocialIdPort {

    private final AtomicLong counter = new AtomicLong(System.currentTimeMillis());

    @Override
    public Long nextId() {
        return counter.incrementAndGet();
    }

    @Override
    public Long now() {
        return System.currentTimeMillis();
    }
}
