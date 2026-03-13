package cn.nexus.domain.social.adapter.port;

import cn.nexus.domain.social.model.valobj.kv.CommentContentItemVO;
import cn.nexus.domain.social.model.valobj.kv.CommentContentKeyVO;
import cn.nexus.domain.social.model.valobj.kv.CommentContentResultVO;

import java.util.List;

/**
 * Comment content KV port.
 */
public interface ICommentContentKvPort {

    void batchAdd(List<CommentContentItemVO> comments);

    List<CommentContentResultVO> batchFind(Long postId, List<CommentContentKeyVO> keys);

    void delete(Long postId, String yearMonth, String contentId);
}
