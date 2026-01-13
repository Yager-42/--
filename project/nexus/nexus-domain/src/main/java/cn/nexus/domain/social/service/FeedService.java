package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IFeedNegativeFeedbackRepository;
import cn.nexus.domain.social.adapter.repository.IFeedTimelineRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ContentPostPageVO;
import cn.nexus.domain.social.model.valobj.FeedItemVO;
import cn.nexus.domain.social.model.valobj.FeedIdPageVO;
import cn.nexus.domain.social.model.valobj.FeedTimelineVO;
import cn.nexus.domain.social.model.valobj.OperationResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 分发与 Feed 服务实现：提供 timeline/profile 与负反馈能力。
 *
 * @author codex
 * @since 2026-01-12
 */
@Service
@RequiredArgsConstructor
public class FeedService implements IFeedService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final IContentRepository contentRepository;
    private final IFeedTimelineRepository feedTimelineRepository;
    private final IFeedNegativeFeedbackRepository feedNegativeFeedbackRepository;
    private final IFeedInboxRebuildService feedInboxRebuildService;

    /**
     * 获取关注页时间线（FOLLOW）：Redis InboxTimeline + 负反馈过滤 + MySQL 回表。
     *
     * <p>Phase 2：仅在首页（cursor 为空）且 inbox key miss 时触发离线重建。</p>
     *
     * @param userId   用户 ID {@link Long}
     * @param cursor   游标（上一页最后一个 postId），为空表示从最新开始 {@link String}
     * @param limit    单页数量（默认 20，最大 100） {@link Integer}
     * @param feedType feed 类型（当前仅支持 FOLLOW，占位兼容） {@link String}
     * @return 时间线结果 {@link FeedTimelineVO}
     */
    @Override
    public FeedTimelineVO timeline(Long userId, String cursor, Integer limit, String feedType) {
        if (userId == null) {
            return FeedTimelineVO.builder().items(List.of()).nextCursor(null).build();
        }
        int normalizedLimit = normalizeLimit(limit);
        String source = (feedType == null || feedType.isBlank()) ? "FOLLOW" : feedType;

        if (cursor == null || cursor.isBlank()) {
            feedInboxRebuildService.rebuildIfNeeded(userId);
        }

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
        Set<Integer> negativeTypes = feedNegativeFeedbackRepository.listContentTypes(userId);
        List<FeedItemVO> items = new ArrayList<>(posts.size());
        for (ContentPostEntity post : posts) {
            if (post == null) {
                continue;
            }
            if (post.getMediaType() != null && negativeTypes.contains(post.getMediaType())) {
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

    /**
     * 获取个人页时间线（PROFILE）：直接从内容仓储分页读取。
     *
     * @param targetId  目标用户 ID {@link Long}
     * @param visitorId 访问者用户 ID {@link Long}
     * @param cursor    游标（\"{lastCreateTimeMs}:{lastPostId}\"），为空表示从最新开始 {@link String}
     * @param limit     单页数量（默认 20，最大 100） {@link Integer}
     * @return 时间线结果 {@link FeedTimelineVO}
     */
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

    /**
     * 提交负反馈：当前实现仅记录 targetId，用于读侧过滤。
     *
     * @param userId     用户 ID {@link Long}
     * @param targetId   目标 ID（通常为 postId） {@link Long}
     * @param type       负反馈类型（占位） {@link String}
     * @param reasonCode 原因码（占位） {@link String}
     * @param extraTags  额外标签（占位） {@link List} {@link String}
     * @return 操作结果 {@link OperationResultVO}
     */
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
        ContentPostEntity post = contentRepository.findPost(targetId);
        if (post != null && post.getMediaType() != null) {
            feedNegativeFeedbackRepository.addContentType(userId, post.getMediaType());
        }
        return OperationResultVO.builder()
                .success(true)
                .id(targetId)
                .status("RECORDED")
                .message(reasonCode)
                .build();
    }

    /**
     * 撤销负反馈。
     *
     * @param userId   用户 ID {@link Long}
     * @param targetId 目标 ID {@link Long}
     * @return 操作结果 {@link OperationResultVO}
     */
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
        ContentPostEntity post = contentRepository.findPost(targetId);
        if (post != null && post.getMediaType() != null) {
            feedNegativeFeedbackRepository.removeContentType(userId, post.getMediaType());
        }
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
