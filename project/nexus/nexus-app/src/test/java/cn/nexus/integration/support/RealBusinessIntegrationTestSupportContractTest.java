package cn.nexus.integration.support;

import static org.assertj.core.api.Assertions.assertThat;

import cn.nexus.domain.social.adapter.port.ISocialIdPort;
import cn.nexus.infrastructure.adapter.id.LeafSnowflakeIdGenerator;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

class RealBusinessIntegrationTestSupportContractTest {

    @Test
    void realBusinessSupport_shouldUseRealIdInfrastructure() {
        Map<String, Field> fieldsByName = Arrays.stream(RealBusinessIntegrationTestSupport.class.getDeclaredFields())
                .collect(Collectors.toMap(Field::getName, field -> field));

        assertThat(fieldsByName).containsKeys("socialIdPort", "leafSnowflakeIdGenerator");
        assertThat(fieldsByName.get("socialIdPort").getType()).isEqualTo(ISocialIdPort.class);
        assertThat(fieldsByName.get("leafSnowflakeIdGenerator").getType()).isEqualTo(LeafSnowflakeIdGenerator.class);
        assertThat(fieldsByName.get("socialIdPort").isAnnotationPresent(MockBean.class)).isFalse();
        assertThat(fieldsByName.get("leafSnowflakeIdGenerator").isAnnotationPresent(MockBean.class)).isFalse();
    }
}
