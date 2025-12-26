package cn.nexus.domain.system.service;

import cn.nexus.domain.system.adapter.port.ISystemStatusPort;
import cn.nexus.domain.system.model.valobj.SystemStatusVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 领域服务实现，通过端口查询系统状态。
 */
@Service
@RequiredArgsConstructor
public class SystemHealthService implements ISystemHealthService {

    private final ISystemStatusPort systemStatusPort;

    @Override
    public SystemStatusVO health() {
        return systemStatusPort.fetchStatus();
    }
}
