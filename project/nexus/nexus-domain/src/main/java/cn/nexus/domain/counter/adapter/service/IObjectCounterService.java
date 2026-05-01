package cn.nexus.domain.counter.adapter.service;

import cn.nexus.domain.counter.model.valobj.ObjectCounterType;
import cn.nexus.domain.social.model.valobj.PostActionResultVO;
import java.util.List;
import java.util.Map;

/**
 * Object counter service contract aligned with zhiguang-style semantics.
 */
public interface IObjectCounterService {

    PostActionResultVO likePost(Long postId, Long userId);

    PostActionResultVO unlikePost(Long postId, Long userId);

    PostActionResultVO favPost(Long postId, Long userId);

    PostActionResultVO unfavPost(Long postId, Long userId);

    boolean isPostLiked(Long postId, Long userId);

    boolean isPostFaved(Long postId, Long userId);

    Map<String, Long> getPostCounts(Long postId, List<ObjectCounterType> metrics);

    Map<Long, Map<String, Long>> getPostCountsBatch(List<Long> postIds, List<ObjectCounterType> metrics);
}
