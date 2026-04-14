package cn.nexus.integration.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RootPomLifecycleContractTest {

    @Test
    void poms_shouldDefineLayeredTestLifecycleForWslRealIt() throws IOException {
        Path moduleDir = Path.of("").toAbsolutePath();
        Path rootPomPath = moduleDir.resolve("..").resolve("pom.xml").normalize();
        Path modulePomPath = moduleDir.resolve("pom.xml").normalize();
        String rootPomXml = Files.readString(rootPomPath);
        String modulePomXml = Files.readString(modulePomPath);

        assertThat(rootPomXml).contains("<id>ci-it</id>");
        assertThat(rootPomXml).contains("<id>local-real-it</id>");
        assertThat(rootPomXml).contains("<skip.real.it.tests>true</skip.real.it.tests>");
        assertThat(modulePomXml).contains("maven-surefire-plugin");
        assertThat(modulePomXml).contains("maven-failsafe-plugin");
        assertThat(modulePomXml).contains("<exclude>**/*IntegrationTest.java</exclude>");
        assertThat(modulePomXml).contains("<include>**/*IntegrationTest.java</include>");
    }
}
