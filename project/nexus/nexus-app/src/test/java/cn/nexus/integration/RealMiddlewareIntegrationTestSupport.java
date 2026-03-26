package cn.nexus.integration;

import cn.nexus.integration.support.RealBusinessIntegrationTestSupport;
import cn.nexus.integration.support.TestNoSchedulingApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = TestNoSchedulingApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles({"test", "wsl", "real-it"})
abstract class RealMiddlewareIntegrationTestSupport extends RealBusinessIntegrationTestSupport {
}
