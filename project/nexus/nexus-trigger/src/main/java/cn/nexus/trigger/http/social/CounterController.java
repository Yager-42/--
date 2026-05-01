package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.counter.dto.PostCounterResponseDTO;
import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1")
public class CounterController {

    @Resource
    private IObjectCounterService objectCounterService;

    @GetMapping("/counter/post/{postId}")
    public Response<PostCounterResponseDTO> postCounter(@PathVariable Long postId,
                                                        @RequestParam(name = "metrics", required = false) String metrics) {
        List<ObjectCounterType> requested = parseMetrics(metrics);
        if (postId == null || requested.isEmpty()) {
            return illegalParameter();
        }
        try {
            Map<String, Long> counts = objectCounterService.getPostCounts(postId, requested);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(),
                    PostCounterResponseDTO.builder().postId(postId).counts(counts).build());
        } catch (AppException e) {
            return Response.<PostCounterResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("post counter api failed, postId={}, metrics={}", postId, metrics, e);
            return Response.<PostCounterResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private List<ObjectCounterType> parseMetrics(String metrics) {
        if (metrics == null || metrics.isBlank()) {
            return List.of(ObjectCounterType.LIKE, ObjectCounterType.FAV);
        }
        List<ObjectCounterType> requested = new ArrayList<>();
        for (String token : metrics.split(",")) {
            String metric = token.trim();
            if ("like".equals(metric)) {
                requested.add(ObjectCounterType.LIKE);
            } else if ("fav".equals(metric)) {
                requested.add(ObjectCounterType.FAV);
            } else {
                return List.of();
            }
        }
        return requested;
    }

    private Response<PostCounterResponseDTO> illegalParameter() {
        return Response.<PostCounterResponseDTO>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                .build();
    }
}
