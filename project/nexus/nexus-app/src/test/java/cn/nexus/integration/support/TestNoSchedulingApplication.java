package cn.nexus.integration.support;

import cn.nexus.Application;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * 集成测试专用启动类：不启用 {@code @EnableScheduling}，避免定时任务干扰真实链路断言。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@AutoConfigurationPackage(basePackageClasses = Application.class)
@ComponentScan(basePackages = "cn.nexus", excludeFilters = {
    @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = Application.class),
    @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = TestConfiguration.class)
})
public class TestNoSchedulingApplication {
}
