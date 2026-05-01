package cn.nexus.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class SearchIndexMappingCountFieldContractTest {

    @Test
    void initializerMappingShouldNotDeclareCountFields() throws Exception {
        SearchIndexInitializer initializer = new SearchIndexInitializer(null);

        Method method = SearchIndexInitializer.class.getDeclaredMethod("buildIndexBody");
        method.setAccessible(true);
        String mapping = String.valueOf(method.invoke(initializer));

        assertThat(mapping)
                .doesNotContain("view_count")
                .doesNotContain("like_count")
                .doesNotContain("favorite_count")
                .doesNotContain("comment_count")
                .doesNotContain("reply_count");
    }
}
