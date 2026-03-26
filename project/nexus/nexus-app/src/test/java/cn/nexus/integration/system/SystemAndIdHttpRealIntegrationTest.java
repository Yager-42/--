package cn.nexus.integration.system;

import static org.assertj.core.api.Assertions.assertThat;

import cn.nexus.integration.support.RealHttpIntegrationTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

class SystemAndIdHttpRealIntegrationTest extends RealHttpIntegrationTestSupport {

    @Test
    void healthAndIdEndpoints_shouldRespondWithExpectedPayloads() throws Exception {
        JsonNode health = assertSuccess(getJson("/api/v1/health", null));
        assertThat(health.path("status").asText()).isNotBlank();

        long segmentId = Long.parseLong(getText("/id/segment/get/it", null).trim());
        assertThat(segmentId).isPositive();

        long snowflakeId = Long.parseLong(getText("/id/snowflake/get/it", null).trim());
        assertThat(snowflakeId).isPositive();
    }
}

