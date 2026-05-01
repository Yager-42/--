package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.action.dto.ActionRequestDTO;
import cn.nexus.api.social.action.dto.ActionResponseDTO;
import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.social.model.valobj.PostActionResultVO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1")
public class ActionController {

    private static final String TARGET_POST = "post";

    @Resource
    private IObjectCounterService objectCounterService;

    @PostMapping("/action/like")
    public Response<ActionResponseDTO> like(@RequestBody ActionRequestDTO requestDTO) {
        return handle(requestDTO, ActionVerb.LIKE);
    }

    @PostMapping("/action/unlike")
    public Response<ActionResponseDTO> unlike(@RequestBody ActionRequestDTO requestDTO) {
        return handle(requestDTO, ActionVerb.UNLIKE);
    }

    @PostMapping("/action/fav")
    public Response<ActionResponseDTO> fav(@RequestBody ActionRequestDTO requestDTO) {
        return handle(requestDTO, ActionVerb.FAV);
    }

    @PostMapping("/action/unfav")
    public Response<ActionResponseDTO> unfav(@RequestBody ActionRequestDTO requestDTO) {
        return handle(requestDTO, ActionVerb.UNFAV);
    }

    private Response<ActionResponseDTO> handle(ActionRequestDTO requestDTO, ActionVerb verb) {
        if (requestDTO == null || !TARGET_POST.equals(requestDTO.getTargetType()) || requestDTO.getTargetId() == null) {
            return illegalParameter();
        }
        try {
            Long userId = UserContext.requireUserId();
            PostActionResultVO result = switch (verb) {
                case LIKE -> objectCounterService.likePost(requestDTO.getTargetId(), userId);
                case UNLIKE -> objectCounterService.unlikePost(requestDTO.getTargetId(), userId);
                case FAV -> objectCounterService.favPost(requestDTO.getTargetId(), userId);
                case UNFAV -> objectCounterService.unfavPost(requestDTO.getTargetId(), userId);
            };
            return Response.success(ResponseCode.SUCCESS.getCode(), ResponseCode.SUCCESS.getInfo(), toResponse(result));
        } catch (AppException e) {
            return Response.<ActionResponseDTO>builder().code(e.getCode()).info(e.getInfo()).build();
        } catch (Exception e) {
            log.error("post action api failed, verb={}, req={}", verb, requestDTO, e);
            return Response.<ActionResponseDTO>builder()
                    .code(ResponseCode.UN_ERROR.getCode())
                    .info(ResponseCode.UN_ERROR.getInfo())
                    .build();
        }
    }

    private ActionResponseDTO toResponse(PostActionResultVO result) {
        return ActionResponseDTO.builder()
                .changed(result.isChanged())
                .liked(result.isLiked())
                .faved(result.isFaved())
                .likeCount(result.getLikeCount())
                .favoriteCount(result.getFavoriteCount())
                .build();
    }

    private Response<ActionResponseDTO> illegalParameter() {
        return Response.<ActionResponseDTO>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(ResponseCode.ILLEGAL_PARAMETER.getInfo())
                .build();
    }

    private enum ActionVerb {
        LIKE,
        UNLIKE,
        FAV,
        UNFAV
    }
}
