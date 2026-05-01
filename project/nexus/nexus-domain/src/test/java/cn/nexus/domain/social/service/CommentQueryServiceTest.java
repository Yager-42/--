package cn.nexus.domain.social.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.adapter.repository.ICommentHotRankRepository;
import cn.nexus.domain.social.adapter.repository.ICommentPinRepository;
import cn.nexus.domain.social.adapter.repository.ICommentRepository;
import cn.nexus.domain.social.adapter.repository.IUserBaseRepository;
import cn.nexus.domain.social.model.valobj.CommentBriefVO;
import cn.nexus.domain.social.model.valobj.CommentViewVO;
import cn.nexus.domain.social.model.valobj.RootCommentPageVO;
import cn.nexus.domain.social.model.valobj.RootCommentViewVO;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CommentQueryServiceTest {

    @Test
    void commentReadModelsShouldNotExposeCounterFields() {
        assertNoFields(CommentViewVO.class, "likeCount", "replyCount", "liked");
        assertNoFields(CommentBriefVO.class, "likeCount", "replyCount", "liked");
        assertNoFields(RootCommentViewVO.class, "likeCount", "replyCount", "liked");
    }

    @Test
    void listRootComments_shouldBatchLoadRepliesPreviewAndFilterVisibility() {
        ICommentRepository commentRepository = Mockito.mock(ICommentRepository.class);
        ICommentPinRepository commentPinRepository = Mockito.mock(ICommentPinRepository.class);
        ICommentHotRankRepository commentHotRankRepository = Mockito.mock(ICommentHotRankRepository.class);
        IUserBaseRepository userBaseRepository = Mockito.mock(IUserBaseRepository.class);

        CommentQueryService svc = new CommentQueryService(
                commentRepository,
                commentPinRepository,
                commentHotRankRepository,
                userBaseRepository);

        Long postId = 100L;
        Long viewerId = 1L;
        Long pinnedId = 200L;
        List<Long> rootIds = List.of(201L, 202L);

        when(commentPinRepository.getPinnedCommentId(postId)).thenReturn(pinnedId);
        when(commentRepository.pageRootCommentIds(postId, pinnedId, null, 20, viewerId)).thenReturn(rootIds);

        Map<Long, CommentViewVO> views = new HashMap<>();
        views.put(200L, CommentViewVO.builder().commentId(200L).postId(postId).userId(2L).rootId(null).status(1).build());
        views.put(201L, CommentViewVO.builder().commentId(201L).postId(postId).userId(3L).rootId(null).status(1).build());
        views.put(202L, CommentViewVO.builder().commentId(202L).postId(postId).userId(4L).rootId(null).status(1).build());

        // replies preview
        views.put(300L, CommentViewVO.builder().commentId(300L).postId(postId).userId(5L).rootId(200L).status(1).build());
        views.put(301L, CommentViewVO.builder().commentId(301L).postId(postId).userId(viewerId).rootId(201L).status(0).build());
        views.put(302L, CommentViewVO.builder().commentId(302L).postId(postId).userId(6L).rootId(201L).status(0).build());
        views.put(303L, CommentViewVO.builder().commentId(303L).postId(postId).userId(7L).rootId(202L).status(1).build());

        when(commentRepository.listByIds(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            if (ids == null || ids.isEmpty()) {
                return List.of();
            }
            List<CommentViewVO> res = new ArrayList<>(ids.size());
            for (Long id : ids) {
                CommentViewVO v = views.get(id);
                if (v != null) {
                    res.add(v);
                }
            }
            return res;
        });

        Map<Long, List<Long>> previewIds = new HashMap<>();
        previewIds.put(200L, List.of(300L));
        previewIds.put(201L, List.of(301L, 302L));
        previewIds.put(202L, List.of(303L));
        when(commentRepository.batchListReplyPreviewIds(anyList(), eq(3), eq(viewerId))).thenReturn(previewIds);

        when(userBaseRepository.listByUserIds(anyList())).thenReturn(List.of());

        RootCommentPageVO page = svc.listRootComments(postId, viewerId, null, 20, 3);

        assertNotNull(page);
        assertNotNull(page.getPinned());
        assertEquals(1, page.getPinned().getRepliesPreview().size());
        assertEquals(300L, page.getPinned().getRepliesPreview().get(0).getCommentId());

        assertEquals(2, page.getItems().size());
        RootCommentViewVO first = page.getItems().get(0);
        assertEquals(201L, first.getRoot().getCommentId());
        assertEquals(1, first.getRepliesPreview().size());
        assertEquals(301L, first.getRepliesPreview().get(0).getCommentId());

        RootCommentViewVO second = page.getItems().get(1);
        assertEquals(202L, second.getRoot().getCommentId());
        assertEquals(1, second.getRepliesPreview().size());
        assertEquals(303L, second.getRepliesPreview().get(0).getCommentId());

        verify(commentRepository, times(1)).batchListReplyPreviewIds(anyList(), eq(3), eq(viewerId));
        verify(commentRepository, never()).pageReplyCommentIds(anyLong(), any(), anyInt(), any());
    }

    private static void assertNoFields(Class<?> type, String... forbiddenNames) {
        Set<String> forbidden = Set.of(forbiddenNames);
        List<String> present = Arrays.stream(type.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .filter(forbidden::contains)
                .toList();
        assertEquals(List.of(), present, type.getName() + " must not expose comment counter state");
    }
}
