package cn.nexus.infrastructure.adapter.social.port;

import static org.assertj.core.api.Assertions.assertThat;

import cn.nexus.domain.social.model.valobj.SearchDocumentVO;
import cn.nexus.domain.social.model.valobj.SearchEngineQueryVO;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchEnginePortCountFieldContractTest {

    @Test
    void searchRequestAndIndexBodyShouldNotContainCountFields() throws Exception {
        SearchEnginePort port = new SearchEnginePort(null);

        String searchBody = invokeJson(port, "buildSearchRequestBody",
                new Class<?>[]{SearchEngineQueryVO.class},
                SearchEngineQueryVO.builder()
                        .keyword("redis")
                        .limit(10)
                        .tags(List.of())
                        .build());
        String indexBody = invokeJson(port, "toIndexBody",
                new Class<?>[]{SearchDocumentVO.class},
                SearchDocumentVO.builder()
                        .contentId(1L)
                        .contentType("POST")
                        .title("title")
                        .description("desc")
                        .body("body")
                        .publishTime(123L)
                        .status("published")
                        .build());

        assertThat(searchBody).doesNotContain("view_count");
        assertThat(indexBody).doesNotContain("view_count");
        assertThat(SearchDocumentVO.class.getDeclaredFields())
                .extracting("name")
                .doesNotContain("viewCount", "likeCount", "favoriteCount", "commentCount", "replyCount");
    }

    private String invokeJson(Object target, String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return String.valueOf(method.invoke(target, args));
    }
}
