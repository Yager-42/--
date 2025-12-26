package cn.nexus;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 应用启动入口。
 */
@SpringBootApplication(scanBasePackages = "cn.nexus")
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
