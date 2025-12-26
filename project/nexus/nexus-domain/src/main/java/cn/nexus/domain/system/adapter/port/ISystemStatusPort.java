package cn.nexus.domain.system.adapter.port;

import cn.nexus.domain.system.model.valobj.SystemStatusVO;

/**
 * 系统状态端口，隔离具体技术实现。
 */
public interface ISystemStatusPort {
    /**
     * 查询系统状态。
     *
     * @return 值对象表示的状态
     */
    SystemStatusVO fetchStatus();
}
