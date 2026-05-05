package cn.nexus.domain.social.service;

import cn.nexus.domain.social.adapter.repository.IFeedAuthorCategoryRepository;
import cn.nexus.domain.social.adapter.repository.IRelationRepository;
import cn.nexus.domain.social.model.valobj.FeedAuthorCategoryEnumVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Feed 作者类别状态机驱动器：根据粉丝数变化推导 NORMAL / BIGV。
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-01
 */
@Service
@RequiredArgsConstructor
public class FeedAuthorCategoryStateMachine {

    private final IRelationRepository relationRepository;
    private final IFeedAuthorCategoryRepository feedAuthorCategoryRepository;

    @Value("${feed.bigv.followerThreshold:500000}")
    private int bigvFollowerThreshold;

    /**
     * 在粉丝数变化后重新计算作者类别。
     *
     * @param authorId 作者 ID。 {@link Long}
     */
    public void onFollowerCountChanged(Long authorId) {
        if (authorId == null) {
            return;
        }

        int followerCount = relationRepository.countFollowerIds(authorId);
        int threshold = Math.max(0, bigvFollowerThreshold);
        // 类别只取决于“当前粉丝数是否越过阈值”，这样状态机没有额外分支，重算也天然幂等。
        int newCategory = (threshold > 0 && followerCount >= threshold)
                ? FeedAuthorCategoryEnumVO.BIGV.getCode()
                : FeedAuthorCategoryEnumVO.NORMAL.getCode();

        Integer oldCategoryRaw = feedAuthorCategoryRepository.getCategory(authorId);
        int oldCategory = oldCategoryRaw == null ? FeedAuthorCategoryEnumVO.NORMAL.getCode() : oldCategoryRaw;
        if (newCategory != oldCategory) {
            feedAuthorCategoryRepository.setCategory(authorId, newCategory);
        }
    }
}
