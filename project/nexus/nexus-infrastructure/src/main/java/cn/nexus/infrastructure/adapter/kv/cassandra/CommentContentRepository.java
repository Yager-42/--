package cn.nexus.infrastructure.adapter.kv.cassandra;

import org.springframework.data.cassandra.repository.CassandraRepository;

import java.util.List;
import java.util.UUID;

public interface CommentContentRepository extends CassandraRepository<CommentContentDO, CommentContentPrimaryKey> {

    List<CommentContentDO> findByPrimaryKeyNoteIdAndPrimaryKeyYearMonthInAndPrimaryKeyContentIdIn(
            Long noteId, List<String> yearMonths, List<UUID> contentIds
    );

    void deleteByPrimaryKeyNoteIdAndPrimaryKeyYearMonthAndPrimaryKeyContentId(
            Long noteId, String yearMonth, UUID contentId
    );
}

