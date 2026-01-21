package cn.nexus.domain.social.service;

import cn.nexus.domain.social.model.valobj.CommentHotVO;
import cn.nexus.domain.social.model.valobj.ReplyCommentPageVO;
import cn.nexus.domain.social.model.valobj.RootCommentPageVO;

/**
 * 评论读侧查询服务（列表/回复/热榜）。
 */
public interface ICommentQueryService {

    /**
     * 一级评论列表（含 pinned + 回复预览）。
     *
     * <p>cursor 协议：nextCursor="{lastCreateTimeMs}:{lastCommentId}"；为空表示从最新开始。</p>
     */
    RootCommentPageVO listRootComments(Long postId, String cursor, Integer limit, Integer preloadReplyLimit);

    /**
     * 楼内回复列表（按时间正序）。
     *
     * <p>cursor 协议：nextCursor="{lastCreateTimeMs}:{lastCommentId}"；为空表示从最早开始。</p>
     */
    ReplyCommentPageVO listReplies(Long rootId, String cursor, Integer limit);

    /**
     * 评论热榜（只排一级评论）。
     */
    CommentHotVO hotComments(Long postId, Integer limit, Integer preloadReplyLimit);
}

