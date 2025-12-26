package cn.nexus.domain.system.service;

import cn.nexus.domain.system.model.valobj.SystemStatusVO;

/**
 * 领域服务接口：系统健康检查。
 */
public interface ISystemHealthService {
    /**
     * 返回当前系统健康状态。
     *
     * @return 系统状态值对象
     */
    SystemStatusVO health();
}
