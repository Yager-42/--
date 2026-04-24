package cn.nexus.trigger.search.support;

import cn.nexus.domain.counter.adapter.service.IObjectCounterService;
import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.adapter.port.ISearchEnginePort;
import cn.nexus.domain.social.adapter.repository.IContentRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.entity.ContentPostEntity;
import cn.nexus.domain.social.model.valobj.ReactionTargetTypeEnumVO;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.types.enums.ContentPostStatusEnumVO;
import cn.nexus.types.enums.ContentPostVisibilityEnumVO;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SearchIndexUpsertService {

    private final ISearchEnginePort searchEnginePort;
    private final IContentRepository contentRepository;
    private final IUserBaseRepository userBaseRepository;
    private final IObjectCounterService objectCounterService;
    private final SearchDocumentAssembler searchDocumentAssembler;

    public SearchIndexAction upsertPost(Long postId) {
        return upsertPost(postId, null);
    }

    public SearchIndexAction upsertPost(Long postId, Long likeCountOverride) {
        ContentPostEntity post = contentRepository.findPostBypassCache(postId);
        if (!indexable(post)) {
            softDelete(postId);
            return SearchIndexAction.softDelete(postId, post == null ? "POST_NOT_FOUND" : "NOT_INDEXABLE");
        }
        SearchDocumentVO document = buildDocument(post, likeCountOverride);
        if (document == null) {
            softDelete(postId);
            return SearchIndexAction.softDelete(postId, "DOCUMENT_INVALID");
        }
        searchEnginePort.upsert(document);
        return SearchIndexAction.upsert(postId);
    }

    public void softDelete(Long postId) {
        if (postId == null) {
            return;
        }
        searchEnginePort.softDelete(postId);
    }

    public long updateAuthorNickname(Long userId) {
        if (userId == null) {
            return 0L;
        }
        UserBriefVO author = resolveAuthor(userId);
        String nickname = author == null ? "" : safe(author.getNickname());
        return searchEnginePort.updateAuthorNickname(userId, nickname);
    }

    private boolean indexable(ContentPostEntity post) {
        if (post == null || post.getPostId() == null) {
            return false;
        }
        if (post.getStatus() == null || post.getStatus() != ContentPostStatusEnumVO.PUBLISHED.getCode()) {
            return false;
        }
        if (post.getVisibility() == null || post.getVisibility() != ContentPostVisibilityEnumVO.PUBLIC.getCode()) {
            return false;
        }
        if (post.getTitle() == null || post.getTitle().isBlank()) {
            return false;
        }
        return post.getPublishTime() != null;
    }

    private SearchDocumentVO buildDocument(ContentPostEntity post, Long likeCountOverride) {
        if (post == null || post.getPostId() == null || post.getTitle() == null || post.getPublishTime() == null) {
            return null;
        }
        UserBriefVO author = resolveAuthor(post.getUserId());
        long likeCount = likeCountOverride == null ? loadLikeCount(post.getPostId()) : likeCountOverride;
        return searchDocumentAssembler.assemble(
                post.getPostId(),
                post.getUserId(),
                post.getTitle(),
                post.getSummary(),
                post.getContentText(),
                post.getPostTypes() == null ? List.of() : post.getPostTypes(),
                author == null ? null : author.getAvatarUrl(),
                author == null ? null : author.getNickname(),
                post.getPublishTime(),
                Math.max(0L, likeCount),
                post.getMediaInfo());
    }

    private long loadLikeCount(Long postId) {
        if (postId == null) {
            return 0L;
        }
        Map<String, Long> counts = objectCounterService.getCounts(
                ReactionTargetTypeEnumVO.POST,
                postId,
                List.of(ObjectCounterType.LIKE));
        Long like = counts == null ? null : counts.get("like");
        return like == null ? 0L : Math.max(0L, like);
    }

    private UserBriefVO resolveAuthor(Long userId) {
        if (userId == null) {
            return null;
        }
        List<UserBriefVO> list = userBaseRepository.listByUserIds(List.of(userId));
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    public record SearchIndexAction(Long postId, boolean upserted, boolean softDeleted, String reason) {

        static SearchIndexAction upsert(Long postId) {
            return new SearchIndexAction(postId, true, false, null);
        }

        static SearchIndexAction softDelete(Long postId, String reason) {
            return new SearchIndexAction(postId, false, true, reason);
        }
    }
}
