package cn.nexus.trigger.http.social;

import cn.nexus.api.response.Response;
import cn.nexus.api.social.IFeedApi;
import cn.nexus.api.social.feed.dto.*;
import cn.nexus.domain.social.model.valobj.FeedItemVO;
import cn.nexus.domain.social.model.valobj.FeedTimelineVO;
import cn.nexus.domain.social.service.IFeedService;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import cn.nexus.trigger.http.support.UserContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.stream.Collectors;

/**
 * Feed 接口入口：负责把 HTTP 请求转换成 Feed 读侧服务调用。
 *
 * @author rr
 * @author codex
 * @since 2025-12-26
 */
@Slf4j
@RestController
@CrossOrigin("*")
@RequestMapping("/api/v1/feed")
public class FeedController implements IFeedApi {

    @Resource
    private IFeedService feedService;

    /**
     * 查询首页时间线。
     *
     * @param requestDTO 时间线请求参数。 {@link FeedTimelineRequestDTO}
     * @return 首页时间线响应。 {@link Response}
     */
    @GetMapping("/timeline")
    @Override
    public Response<FeedTimelineResponseDTO> timeline(FeedTimelineRequestDTO requestDTO) {
        try {
            // 控制层永远只信登录态里的用户 ID，不接受前端伪造的 userId。
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

    /**
     * 查询指定作者的主页时间线。
     *
     * @param targetId 被访问作者 ID。 {@link Long}
     * @param requestDTO 主页时间线请求参数。 {@link ProfileFeedRequestDTO}
     * @return 主页时间线响应。 {@link Response}
     */
    @GetMapping("/profile/{targetId}")
    @Override
    public Response<FeedTimelineResponseDTO> profile(@PathVariable("targetId") Long targetId, ProfileFeedRequestDTO requestDTO) {
        try {
            // visitorId 同样只从上下文拿，保证主页关系态和拉黑判断基于真实访问者。
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
                .authorNickname(vo.getAuthorNickname())
                .authorAvatar(vo.getAuthorAvatar())
                .text(vo.getText())
                .summary(vo.getSummary())
                .mediaType(vo.getMediaType())
                .mediaInfo(vo.getMediaInfo())
                .publishTime(vo.getPublishTime())
                .source(vo.getSource())
                .likeCount(vo.getLikeCount())
                .liked(vo.getLiked())
                .followed(vo.getFollowed())
                .seen(vo.getSeen())
                .build();
    }
}
