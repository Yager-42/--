package cn.nexus.integration.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RealItProfileContractTest {

    @Test
    void realItProfile_shouldUseWslBackedLeafSnowflake() throws IOException {
        Path resource = Path.of("src", "test", "resources", "application-real-it.yml");
        String yaml = Files.readString(resource);

        assertThat(yaml).contains("leaf:");
        assertThat(yaml).contains("enabled: true");
        assertThat(yaml).contains("zk-address:");
    }
}
