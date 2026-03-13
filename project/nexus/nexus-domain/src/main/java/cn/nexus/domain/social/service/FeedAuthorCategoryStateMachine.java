package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.valobj.FeedAuthorCategoryEnumVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Feed 作者类别状态机驱动器：根据粉丝数变化推导 NORMAL/BIGV，并在切换时触发 Outbox 重建。
 */
@Service
@RequiredArgsConstructor
public class FeedAuthorCategoryStateMachine {

    private final IRelationRepository relationRepository;
    private final IFeedAuthorCategoryRepository feedAuthorCategoryRepository;
    private final IFeedOutboxRebuildService feedOutboxRebuildService;

    @Value("${feed.bigv.followerThreshold:500000}")
    private int bigvFollowerThreshold;

    public void onFollowerCountChanged(Long authorId) {
        if (authorId == null) {
            return;
        }

        int followerCount = relationRepository.countFollowerIds(authorId);
        int threshold = Math.max(0, bigvFollowerThreshold);
        int newCategory = (threshold > 0 && followerCount >= threshold)
                ? FeedAuthorCategoryEnumVO.BIGV.getCode()
                : FeedAuthorCategoryEnumVO.NORMAL.getCode();

        Integer oldCategoryRaw = feedAuthorCategoryRepository.getCategory(authorId);
        int oldCategory = oldCategoryRaw == null ? FeedAuthorCategoryEnumVO.NORMAL.getCode() : oldCategoryRaw;
        if (newCategory != oldCategory) {
            feedAuthorCategoryRepository.setCategory(authorId, newCategory);
            feedOutboxRebuildService.forceRebuild(authorId);
        }
    }
}

