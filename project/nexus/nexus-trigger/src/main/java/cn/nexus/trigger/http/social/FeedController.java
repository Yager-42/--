package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IFeedApi;
import cn.nexus.api.social.common.OperationResultDTO;
import cn.nexus.api.social.feed.dto.*;
import cn.nexus.domain.social.model.valobj.FeedItemVO;
import cn.nexus.domain.social.model.valobj.FeedTimelineVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.service.IFeedService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

/**
 * Feed 接口入口。
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/feed")
public class FeedController implements IFeedApi {

    @Resource
    private IFeedService feedService;

    @GetMapping("/timeline")
    @Override
    public Response<FeedTimelineResponseDTO> timeline(FeedTimelineRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            FeedTimelineVO vo = feedService.timeline(userId, requestDTO.getCursor(), requestDTO.getLimit(), requestDTO.getFeedType());
            FeedTimelineResponseDTO dto = toTimelineDTO(vo);
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
        } catch (AppException e) {
            return Response.<FeedTimelineResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("feed timeline api failed, req={}", requestDTO, e);
            return Response.<FeedTimelineResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @GetMapping("/profile/{targetId}")
    @Override
    public Response<FeedTimelineResponseDTO> profile(@PathVariable("targetId") Long targetId, ProfileFeedRequestDTO requestDTO) {
        try {
            Long visitorId = UserContext.requireUserId();
            FeedTimelineVO vo = feedService.profile(targetId, visitorId, requestDTO.getCursor(), requestDTO.getLimit());
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toTimelineDTO(vo));
        } catch (AppException e) {
            return Response.<FeedTimelineResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("feed profile api failed, targetId={}, req={}", targetId, requestDTO, e);
            return Response.<FeedTimelineResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @PostMapping("/feedback/negative")
    @Override
    public Response<OperationResultDTO> submitNegativeFeedback(@RequestBody NegativeFeedbackRequestDTO requestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = feedService.negativeFeedback(
                    userId, requestDTO.getTargetId(), requestDTO.getType(), requestDTO.getReasonCode(), requestDTO.getExtraTags());
            return toOperationResult(vo);
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("feed negative feedback api failed, req={}", requestDTO, e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    @DeleteMapping("/feedback/negative/{targetId}")
    @Override
    public Response<OperationResultDTO> cancelNegativeFeedback(@PathVariable("targetId") Long targetId,
                                                               @RequestBody CancelNegativeFeedbackRequestDTO ignoredRequestDTO) {
        try {
            Long userId = UserContext.requireUserId();
            OperationResultVO vo = feedService.cancelNegativeFeedback(userId, targetId);
            return toOperationResult(vo);
        } catch (AppException e) {
            return Response.<OperationResultDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("feed cancel negative feedback api failed, targetId={}", targetId, e);
            return Response.<OperationResultDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private FeedTimelineResponseDTO toTimelineDTO(FeedTimelineVO vo) {
        return FeedTimelineResponseDTO.builder()
                .items(vo.getItems().stream().map(this::toItem).collect(Collectors.toList()))
                .nextCursor(vo.getNextCursor())
                .build();
    }

    private FeedItemDTO toItem(FeedItemVO vo) {
        return FeedItemDTO.builder()
                .postId(vo.getPostId())
                .authorId(vo.getAuthorId())
                .text(vo.getText())
                .publishTime(vo.getPublishTime())
                .source(vo.getSource())
                .build();
    }

    private Response<OperationResultDTO> toOperationResult(OperationResultVO vo) {
        OperationResultDTO dto = OperationResultDTO.builder()
                .success(vo.isSuccess())
                .id(vo.getId())
                .status(vo.getStatus())
                .message(vo.getMessage())
                .build();
        return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), dto);
    }
}
