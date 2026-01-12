package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedNegativeFeedbackRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.FeedItemVO;
import cn.nexus.domain.social.model.valobj.FeedIdPageVO;
import cn.nexus.domain.social.model.valobj.FeedTimelineVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 分发服务实现。
 */
@Service
@RequiredArgsConstructor
public class FeedService implements IFeedService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final IContentRepository contentRepository;
    private final IFeedTimelineRepository feedTimelineRepository;
    private final IFeedNegativeFeedbackRepository feedNegativeFeedbackRepository;

    @Override
    public FeedTimelineVO timeline(Long userId, String cursor, Integer limit, String feedType) {
        if (userId == null) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(null).build();
        }
        int normalizedLimit = normalizeLimit(limit);
        String source = (feedType == null || feedType.isBlank()) ? "FOLLOW" : feedType;

        FeedIdPageVO page = feedTimelineRepository.pageInbox(userId, cursor, normalizedLimit);
        List<Long> ids = page.getPostIds() == null ? List.of() : page.getPostIds();
        if (ids.isEmpty()) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(page.getNextCursor()).build();
        }

        List<Long> filtered = new ArrayList<>(ids.size());
        for (Long id : ids) {
            if (id == null) {
                continue;
            }
            if (!feedNegativeFeedbackRepository.contains(userId, id)) {
                filtered.add(id);
            }
        }

        List<ContentPostEntity> posts = contentRepository.listPostsByIds(filtered);
        List<FeedItemVO> items = new ArrayList<>(posts.size());
        for (ContentPostEntity post : posts) {
            if (post == null) {
                continue;
            }
            items.add(FeedItemVO.builder()
                    .postId(post.getPostId())
                    .authorId(post.getUserId())
                    .text(post.getContentText())
                    .publishTime(post.getCreateTime())
                    .source(source)
                    .build());
        }
        return FeedTimelineVO.builder().items(items).nextCursor(page.getNextCursor()).build();
    }

    @Override
    public FeedTimelineVO profile(Long targetId, Long visitorId, String cursor, Integer limit) {
        if (targetId == null) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(null).build();
        }
        int normalizedLimit = normalizeLimit(limit);
        ContentPostPageVO page = contentRepository.listUserPosts(targetId, cursor, normalizedLimit);
        List<ContentPostEntity> posts = page.getPosts() == null ? List.of() : page.getPosts();
        if (posts.isEmpty()) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(page.getNextCursor()).build();
        }
        List<FeedItemVO> items = new ArrayList<>(posts.size());
        for (ContentPostEntity post : posts) {
            if (post == null) {
                continue;
            }
            items.add(FeedItemVO.builder()
                    .postId(post.getPostId())
                    .authorId(post.getUserId())
                    .text(post.getContentText())
                    .publishTime(post.getCreateTime())
                    .source("PROFILE")
                    .build());
        }
        return FeedTimelineVO.builder().items(items).nextCursor(page.getNextCursor()).build();
    }

    @Override
    public OperationResultVO negativeFeedback(Long userId, Long targetId, String type, String reasonCode, List<String> extraTags) {
        if (userId == null || targetId == null) {
            return OperationResultVO.builder()
                    .success(false)
                    .id(targetId)
                    .status("INVALID")
                    .message("参数错误")
                    .build();
        }
        feedNegativeFeedbackRepository.add(userId, targetId, type, reasonCode);
        return OperationResultVO.builder()
                .success(true)
                .id(targetId)
                .status("RECORDED")
                .message(reasonCode)
                .build();
    }

    @Override
    public OperationResultVO cancelNegativeFeedback(Long userId, Long targetId) {
        if (userId == null || targetId == null) {
            return OperationResultVO.builder()
                    .success(false)
                    .id(targetId)
                    .status("INVALID")
                    .message("参数错误")
                    .build();
        }
        feedNegativeFeedbackRepository.remove(userId, targetId);
        return OperationResultVO.builder()
                .success(true)
                .id(targetId)
                .status("CANCELLED")
                .message("已撤销负反馈")
                .build();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
