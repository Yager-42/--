package cn.nexus.trigger.http.id;

import cn.nexus.api.response.Response;
import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.infrastructure.adapter.id.LeafSegmentIdService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ID service endpoints.
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/id")
@RequiredArgsConstructor
public class IdController {

    private final LeafSegmentIdService leafSegmentIdService;
    private final ISocialIdPort socialIdPort;

    @GetMapping("/segment/get/{bizTag}")
    public Response<String> segment(@PathVariable("bizTag") String bizTag) {
        try {
            long id = leafSegmentIdService.nextId(bizTag);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), String.valueOf(id));
        } catch (IllegalArgumentException e) {
            return Response.<String>builder().code(ResponseCode.ILLEGAL_PARAMETER.getCode()).info(e.getMessage()).build();
        } catch (AppException e) {
            return Response.<String>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("segment id api failed, bizTag={}", bizTag, e);
            return Response.<String>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }

    @GetMapping("/snowflake/get/{key}")
    public Response<String> snowflake(@PathVariable("key") String key) {
        try {
            Long id = socialIdPort.nextId();
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), String.valueOf(id));
        } catch (AppException e) {
            return Response.<String>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("snowflake id api failed, key={}", key, e);
            return Response.<String>builder().code(ResponseCode.UN_ERROR.getCode()).info(ResponseCode.UN_ERROR.getInfo()).build();
        }
    }
}
