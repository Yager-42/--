package cn.nexus.trigger.search.support;

import static org.assertj.core.api.Assertions.assertThat;

import cn.nexus.domain.social.adapter.port.IMediaStoragePort;
import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SearchDocumentAssemblerTest {

    @Test
    void assembledDocumentShouldCarrySearchableFieldsWithoutCounterFields() {
        IMediaStoragePort mediaStoragePort = Mockito.mock(IMediaStoragePort.class);
        Mockito.when(mediaStoragePort.generateReadUrl("media-1")).thenReturn("https://cdn/media-1");
        SearchDocumentAssembler assembler = new SearchDocumentAssembler(mediaStoragePort);

        SearchDocumentVO document = assembler.assemble(
                101L,
                11L,
                "title",
                "summary",
                "body",
                List.of("tag"),
                "avatar",
                "author",
                123L,
                "media-1");

        assertThat(document.getContentId()).isEqualTo(101L);
        assertThat(document.getContentType()).isEqualTo("POST");
        assertThat(document.getImgUrls()).containsExactly("https://cdn/media-1");
        assertThat(counterFieldsOn(SearchDocumentVO.class))
                .as("search documents must not persist counter values")
                .isEmpty();
    }

    private static List<String> counterFieldsOn(Class<?> type) {
        Set<String> forbidden = Set.of("likeCount", "favoriteCount", "commentCount", "replyCount", "viewCount");
        return Arrays.stream(type.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .filter(forbidden::contains)
                .toList();
    }
}
