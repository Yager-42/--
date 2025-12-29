package cn.nexus.infrastructure.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * 统一扫描基础设施层的 MyBatis Mapper，避免因未注册导致上层注入失败。
 */
@Configuration
@MapperScan("cn.nexus.infrastructure.dao")
public class MybatisConfig {
}
