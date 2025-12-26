package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.domain.social.model.valobj.FeedItemVO;
import cn.nexus.domain.social.model.valobj.FeedTimelineVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分发服务实现。
 */
@Service
@RequiredArgsConstructor
public class FeedService implements IFeedService {

    private final ISocialIdPort socialIdPort;

    @Override
    public FeedTimelineVO timeline(Long userId, String cursor, Integer limit, String feedType) {
        FeedItemVO item = FeedItemVO.builder()
                .postId(socialIdPort.nextId())
                .authorId(userId)
                .text("关注流占位内容")
                .publishTime(socialIdPort.now())
                .source(feedType != null ? feedType : "FOLLOW")
                .build();
        return FeedTimelineVO.builder()
                .items(List.of(item))
                .nextCursor("next-" + socialIdPort.nextId())
                .build();
    }

    @Override
    public FeedTimelineVO profile(Long targetId, Long visitorId, String cursor, Integer limit) {
        FeedItemVO item = FeedItemVO.builder()
                .postId(socialIdPort.nextId())
                .authorId(targetId)
                .text("个人页占位内容")
                .publishTime(socialIdPort.now())
                .source("PROFILE")
                .build();
        return FeedTimelineVO.builder()
                .items(List.of(item))
                .nextCursor("next-" + socialIdPort.nextId())
                .build();
    }

    @Override
    public OperationResultVO negativeFeedback(Long userId, Long targetId, String type, String reasonCode, List<String> extraTags) {
        return OperationResultVO.builder()
                .success(true)
                .id(targetId)
                .status("RECORDED")
                .message(reasonCode)
                .build();
    }

    @Override
    public OperationResultVO cancelNegativeFeedback(Long userId, Long targetId) {
        return OperationResultVO.builder()
                .success(true)
                .id(targetId)
                .status("CANCELLED")
                .message("已撤销负反馈")
                .build();
    }
}
