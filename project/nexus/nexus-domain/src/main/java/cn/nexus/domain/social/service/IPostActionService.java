package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.PostActionResultVO;

public interface IPostActionService {

    PostActionResultVO likePost(Long postId, Long userId, String requestId);

    PostActionResultVO unlikePost(Long postId, Long userId, String requestId);

    PostActionResultVO favPost(Long postId, Long userId, String requestId);

    PostActionResultVO unfavPost(Long postId, Long userId, String requestId);
}
