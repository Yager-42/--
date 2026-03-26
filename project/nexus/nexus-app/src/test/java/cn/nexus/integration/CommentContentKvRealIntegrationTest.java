package cn.nexus.integration;

import static org.assertj.core.api.Assertions.assertThat;

import cn.nexus.domain.social.model.valobj.kv.CommentContentItemVO;
import cn.nexus.domain.social.model.valobj.kv.CommentContentKeyVO;
import cn.nexus.domain.social.model.valobj.kv.CommentContentResultVO;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CommentContentKvRealIntegrationTest extends RealMiddlewareIntegrationTestSupport {

    @Test
    void commentContentKv_shouldBatchPersistQueryAndDeleteThroughCassandra() {
        long postId = uniqueId();
        String yearMonth = "202603";
        String firstContentId = uniqueUuid();
        String secondContentId = uniqueUuid();

        commentContentKvPort.batchAdd(List.of(
                CommentContentItemVO.builder()
                        .postId(postId)
                        .yearMonth(yearMonth)
                        .contentId(firstContentId)
                        .content("第一条真实评论正文")
                        .build(),
                CommentContentItemVO.builder()
                        .postId(postId)
                        .yearMonth(yearMonth)
                        .contentId(secondContentId)
                        .content("第二条真实评论正文")
                        .build()
        ));

        List<CommentContentResultVO> loaded = commentContentKvPort.batchFind(postId, List.of(
                CommentContentKeyVO.builder().yearMonth(yearMonth).contentId(firstContentId).build(),
                CommentContentKeyVO.builder().yearMonth(yearMonth).contentId(secondContentId).build()
        ));

        Map<String, String> contentById = loaded.stream()
                .collect(Collectors.toMap(CommentContentResultVO::getContentId, CommentContentResultVO::getContent));

        assertThat(contentById)
                .containsEntry(firstContentId, "第一条真实评论正文")
                .containsEntry(secondContentId, "第二条真实评论正文");

        commentContentKvPort.delete(postId, yearMonth, firstContentId);

        List<CommentContentResultVO> afterDelete = commentContentKvPort.batchFind(postId, List.of(
                CommentContentKeyVO.builder().yearMonth(yearMonth).contentId(firstContentId).build(),
                CommentContentKeyVO.builder().yearMonth(yearMonth).contentId(secondContentId).build()
        ));

        assertThat(afterDelete)
                .extracting(CommentContentResultVO::getContentId)
                .containsExactly(secondContentId);
    }
}
