package cn.nexus.infrastructure.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索域 Elasticsearch 客户端配置（官方 ES 8 Java Client）。
 *
 * <p>本次不讨论鉴权细节：网关/基础设施负责。</p>
 */
@Configuration
public class SearchElasticsearchConfig {

    @Value("${search.es.endpoints:http://127.0.0.1:9200}")
    private String endpoints;

    @Bean(destroyMethod = "close")
    public RestClient searchRestClient() {
        return RestClient.builder(parseHosts(endpoints)).build();
    }

    @Bean
    public ElasticsearchTransport searchElasticsearchTransport(RestClient searchRestClient) {
        return new RestClientTransport(searchRestClient, new JacksonJsonpMapper());
    }

    @Bean
    public ElasticsearchClient searchElasticsearchClient(ElasticsearchTransport searchElasticsearchTransport) {
        return new ElasticsearchClient(searchElasticsearchTransport);
    }

    private HttpHost[] parseHosts(String raw) {
        String input = raw == null ? "" : raw.trim();
        if (input.isEmpty()) {
            input = "http://127.0.0.1:9200";
        }
        String[] parts = input.split(",");
        List<HttpHost> hosts = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part == null) {
                continue;
            }
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            hosts.add(HttpHost.create(s));
        }
        if (hosts.isEmpty()) {
            hosts.add(HttpHost.create("http://127.0.0.1:9200"));
        }
        return hosts.toArray(new HttpHost[0]);
    }
}

