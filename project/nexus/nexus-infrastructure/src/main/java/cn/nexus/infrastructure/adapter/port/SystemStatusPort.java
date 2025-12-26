package cn.nexus.infrastructure.adapter.port;

import cn.nexus.domain.system.adapter.port.ISystemStatusPort;
import cn.nexus.domain.system.model.valobj.SystemStatusVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 系统状态技术实现，可扩展为真实监控/存储查询。
 */
@Slf4j
@Component
public class SystemStatusPort implements ISystemStatusPort {
    @Override
    public SystemStatusVO fetchStatus() {
        // 目前返回静态信息，后续可接入监控与依赖检查。
        log.debug("获取系统状态：返回静态健康信息");
        return SystemStatusVO.builder()
                .status("OK")
                .build();
    }
}
