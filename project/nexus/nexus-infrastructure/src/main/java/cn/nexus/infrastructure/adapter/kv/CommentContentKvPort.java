package cn.nexus.infrastructure.adapter.kv;

import cn.nexus.domain.social.adapter.port.ICommentContentKvPort;
import cn.nexus.domain.social.model.valobj.kv.CommentContentItemVO;
import cn.nexus.domain.social.model.valobj.kv.CommentContentKeyVO;
import cn.nexus.domain.social.model.valobj.kv.CommentContentResultVO;
import cn.nexus.infrastructure.adapter.kv.cassandra.CommentContentDO;
import cn.nexus.infrastructure.adapter.kv.cassandra.CommentContentPrimaryKey;
import cn.nexus.infrastructure.adapter.kv.cassandra.CommentContentRepository;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.cassandra.core.CassandraTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class CommentContentKvPort implements ICommentContentKvPort {

    private final CassandraTemplate cassandraTemplate;
    private final CommentContentRepository commentContentRepository;

    @Override
    public void batchAdd(List<CommentContentItemVO> comments) {
        if (comments == null || comments.isEmpty()) {
            return;
        }
        List<CommentContentDO> list = new ArrayList<>(comments.size());
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
            UUID contentId = tryParseUuid(c.getContentId());
            if (contentId == null) {
                continue;
            }
            CommentContentPrimaryKey pk = CommentContentPrimaryKey.builder()
                    .noteId(c.getPostId())
                    .yearMonth(c.getYearMonth().trim())
                    .contentId(contentId)
                    .build();
            list.add(CommentContentDO.builder().primaryKey(pk).content(c.getContent()).build());
        }
        if (list.isEmpty()) {
            return;
        }
        cassandraTemplate.batchOps().insert(list).execute();
    }

    @Override
    public List<CommentContentResultVO> batchFind(Long postId, List<CommentContentKeyVO> keys) {
        if (postId == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "postId 不能为空");
        }
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        Set<String> yearMonths = new LinkedHashSet<>();
        Set<UUID> contentIds = new LinkedHashSet<>();
        for (CommentContentKeyVO k : keys) {
            if (k == null) {
                continue;
            }
            String ym = normalize(k.getYearMonth());
            String cid = normalize(k.getContentId());
            if (ym == null || cid == null) {
                continue;
            }
            UUID uuid = tryParseUuid(cid);
            if (uuid == null) {
                continue;
            }
            yearMonths.add(ym);
            contentIds.add(uuid);
        }
        if (yearMonths.isEmpty() || contentIds.isEmpty()) {
            return List.of();
        }

        List<CommentContentDO> rows = commentContentRepository
                .findByPrimaryKeyNoteIdAndPrimaryKeyYearMonthInAndPrimaryKeyContentIdIn(
                        postId, new ArrayList<>(yearMonths), new ArrayList<>(contentIds));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<CommentContentResultVO> out = new ArrayList<>(rows.size());
        for (CommentContentDO po : rows) {
            if (po == null || po.getPrimaryKey() == null || po.getPrimaryKey().getContentId() == null) {
                continue;
            }
            out.add(CommentContentResultVO.builder()
                    .contentId(String.valueOf(po.getPrimaryKey().getContentId()))
                    .content(po.getContent())
                    .build());
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
        UUID uuid = tryParseUuid(cid);
        if (uuid == null) {
            return;
        }
        commentContentRepository.deleteByPrimaryKeyNoteIdAndPrimaryKeyYearMonthAndPrimaryKeyContentId(postId, ym, uuid);
    }

    private String normalize(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private UUID tryParseUuid(String s) {
        if (s == null) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (Exception ignored) {
            return null;
        }
    }
}
