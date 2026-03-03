package cn.nexus.infrastructure.adapter.kv;

import cn.nexus.domain.social.adapter.port.ICommentContentKvPort;
import cn.nexus.domain.social.model.valobj.kv.CommentContentItemVO;
import cn.nexus.domain.social.model.valobj.kv.CommentContentKeyVO;
import cn.nexus.domain.social.model.valobj.kv.CommentContentResultVO;
import cn.nexus.infrastructure.dao.kv.ICommentContentDao;
import cn.nexus.infrastructure.dao.kv.po.CommentContentKeyPO;
import cn.nexus.infrastructure.dao.kv.po.CommentContentPO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CommentContentKvPort implements ICommentContentKvPort {

    private static final int BATCH_SIZE = 500;

    private final ICommentContentDao commentContentDao;

    @Override
    public void batchAdd(List<CommentContentItemVO> comments) {
        if (comments == null || comments.isEmpty()) {
            return;
        }
        List<CommentContentPO> list = new ArrayList<>(comments.size());
        for (CommentContentItemVO c : comments) {
            if (c == null) {
                continue;
            }
            if (c.getPostId() == null || c.getYearMonth() == null || c.getYearMonth().isBlank() || c.getContentId() == null || c.getContentId().isBlank()) {
                continue;
            }
            if (c.getContent() == null) {
                continue;
            }
            CommentContentPO po = new CommentContentPO();
            po.setPostId(c.getPostId());
            po.setYearMonth(c.getYearMonth().trim());
            po.setContentId(c.getContentId().trim());
            po.setContent(c.getContent());
            list.add(po);
        }
        if (list.isEmpty()) {
            return;
        }

        for (int i = 0; i < list.size(); i += BATCH_SIZE) {
            int end = Math.min(list.size(), i + BATCH_SIZE);
            commentContentDao.batchUpsert(list.subList(i, end));
        }
    }

    @Override
    public List<CommentContentResultVO> batchFind(Long postId, List<CommentContentKeyVO> keys) {
        if (postId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "postId 不能为空");
        }
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        List<CommentContentKeyPO> list = new ArrayList<>(keys.size());
        for (CommentContentKeyVO k : keys) {
            if (k == null) {
                continue;
            }
            String ym = normalize(k.getYearMonth());
            String cid = normalize(k.getContentId());
            if (ym == null || cid == null) {
                continue;
            }
            list.add(new CommentContentKeyPO(ym, cid));
        }
        if (list.isEmpty()) {
            return List.of();
        }

        List<CommentContentPO> rows = commentContentDao.batchSelect(postId, list);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<CommentContentResultVO> out = new ArrayList<>(rows.size());
        for (CommentContentPO po : rows) {
            if (po == null || po.getContentId() == null) {
                continue;
            }
            out.add(CommentContentResultVO.builder().contentId(po.getContentId()).content(po.getContent()).build());
        }
        return out;
    }

    @Override
    public void delete(Long postId, String yearMonth, String contentId) {
        if (postId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "postId 不能为空");
        }
        String ym = normalize(yearMonth);
        String cid = normalize(contentId);
        if (ym == null || cid == null) {
            return;
        }
        commentContentDao.delete(postId, ym, cid);
    }

    private String normalize(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
