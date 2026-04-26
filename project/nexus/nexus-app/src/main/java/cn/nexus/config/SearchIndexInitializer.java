package cn.nexus.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * SearchIndexInitializer 配置类。
 *
 * @author m0_52354773
 * @author codex
 * @since 2026-03-09
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SearchIndexInitializer implements ApplicationRunner {

    @Value("${search.es.indexAlias:zhiguang_content_index}")
    private String indexAlias;

    private final RestClient searchRestClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 执行启动任务。
     *
     * @param args args 参数。类型：{@link ApplicationArguments}
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            if (exists()) {
                return;
            }
            Request request = new Request("PUT", "/" + indexAlias);
            request.setJsonEntity(buildIndexBody().toString());
            searchRestClient.performRequest(request);
            log.info("search index created, index={}", indexAlias);
        } catch (Exception e) {
            log.warn("search index initialize failed, index={}", indexAlias, e);
        }
    }

    private boolean exists() {
        try {
            searchRestClient.performRequest(new Request("HEAD", "/" + indexAlias));
            return true;
        } catch (ResponseException e) {
            return false;
        } catch (Exception e) {
            log.warn("search index exists check failed, index={}", indexAlias, e);
            return false;
        }
    }

    private ObjectNode buildIndexBody() {
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode properties = root.putObject("mappings").putObject("properties");
        properties.putObject("content_id").put("type", "long");
        properties.putObject("content_type").put("type", "keyword");
        textField(properties, "description");
        textField(properties, "title");
        textField(properties, "body");
        properties.putObject("tags").put("type", "keyword");
        properties.putObject("author_id").put("type", "long");
        properties.putObject("author_avatar").put("type", "keyword");
        properties.putObject("author_nickname").put("type", "keyword");
        properties.putObject("author_tag_json").put("type", "keyword");
        properties.putObject("publish_time").put("type", "date").put("format", "epoch_millis");
        properties.putObject("status").put("type", "keyword");
        properties.putObject("img_urls").put("type", "keyword");
        properties.putObject("is_top").put("type", "keyword");
        properties.putObject("title_suggest").put("type", "completion");
        return root;
    }

    private void textField(ObjectNode properties, String field) {
        properties.putObject(field)
                .put("type", "text")
                .put("analyzer", "ik_max_word")
                .put("search_analyzer", "ik_smart");
    }
}
