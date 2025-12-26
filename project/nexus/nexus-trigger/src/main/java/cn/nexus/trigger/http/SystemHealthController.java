package cn.nexus.trigger.http;

import cn.nexus.api.ISystemHealthApi;
import cn.nexus.api.dto.SystemHealthResponseDTO;
import cn.nexus.api.response.Response;
import cn.nexus.domain.system.model.valobj.SystemStatusVO;
import cn.nexus.domain.system.service.ISystemHealthService;
import cn.nexus.types.enums.ResponseCode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查入口，实现 API 契约。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/health")
public class SystemHealthController implements ISystemHealthApi {

    @Resource
    private ISystemHealthService systemHealthService;

    @Override
    @GetMapping
    public Response<SystemHealthResponseDTO> health() {
        SystemStatusVO status = systemHealthService.health();
        log.debug("健康检查结果: {}", status.getStatus());
        return Response.success(
                ResponseCode.SUCCESS.getCode(),
                ResponseCode.SUCCESS.getInfo(),
                SystemHealthResponseDTO.builder().status(status.getStatus()).build()
        );
    }
}
