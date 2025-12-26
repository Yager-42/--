package cn.nexus.api;

import cn.nexus.api.dto.SystemHealthResponseDTO;
import cn.nexus.api.response.Response;

/**
 * 系统健康接口定义。
 */
public interface ISystemHealthApi {

    /**
     * 健康检查，供触发器层实现。
     *
     * @return 统一响应
     */
    Response<SystemHealthResponseDTO> health();
}
