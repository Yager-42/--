package cn.nexus.infrastructure.dao.auth;

import cn.nexus.infrastructure.dao.auth.po.AuthAccountPO;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthMapperContractTest {

    private static Configuration configuration;

    @BeforeAll
    static void setUpConfiguration() throws Exception {
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(IAuthAccountDao.class);
        parseMapper("mapper/auth/AuthAccountMapper.xml");
    }

    @Test
    void authAccountMapper_shouldSupportFindByPhoneInsertUpdatePasswordAndTouchLastLogin() {
        AuthAccountPO account = new AuthAccountPO();
        account.setUserId(1001L);
        account.setPhone("13800000001");
        account.setPasswordHash("hash-v1");
        account.setPasswordUpdatedAt(new Date());

        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthAccountDao.selectByPhone",
                Map.of("phone", "13800000001"),
                "from auth_account",
                "where phone = ?"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthAccountDao.selectByPhone",
                Map.of("phone", "13800000001"),
                "phone"
        );

        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthAccountDao.selectByUserId",
                Map.of("userId", 1001L),
                "from auth_account",
                "where user_id = ?"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthAccountDao.selectByUserId",
                Map.of("userId", 1001L),
                "userId"
        );

        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthAccountDao.insert",
                account,
                "insert into auth_account",
                "user_id",
                "phone",
                "password_hash",
                "password_updated_at",
                "last_login_at",
                "now()"
        );
        assertGeneratedKey(
                "cn.nexus.infrastructure.dao.auth.IAuthAccountDao.insert",
                "accountId"
        );

        Map<String, Object> updatePasswordParam = Map.of(
                "userId", 1001L,
                "passwordHash", "hash-v2",
                "passwordUpdatedAt", new Date()
        );
        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthAccountDao.updatePassword",
                updatePasswordParam,
                "update auth_account",
                "set password_hash = ?",
                "password_updated_at = ?",
                "update_time = now()",
                "where user_id = ?"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthAccountDao.updatePassword",
                updatePasswordParam,
                "passwordHash",
                "passwordUpdatedAt",
                "userId"
        );

        Map<String, Object> touchLastLoginParam = Map.of(
                "userId", 1001L,
                "lastLoginAt", new Date()
        );
        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthAccountDao.touchLastLogin",
                touchLastLoginParam,
                "update auth_account",
                "set last_login_at = ?",
                "update_time = now()",
                "where user_id = ?"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthAccountDao.touchLastLogin",
                touchLastLoginParam,
                "lastLoginAt",
                "userId"
        );
    }

    private static void parseMapper(String resource) throws IOException {
        try (InputStream inputStream = Resources.getResourceAsStream(resource)) {
            XMLMapperBuilder xmlMapperBuilder = new XMLMapperBuilder(
                    inputStream,
                    configuration,
                    resource,
                    configuration.getSqlFragments()
            );
            xmlMapperBuilder.parse();
        }
    }

    private void assertSqlContains(String statementId, Object parameterObject, String... fragments) {
        String normalizedSql = normalizeSql(mappedStatement(statementId).getBoundSql(parameterObject));
        for (String fragment : fragments) {
            assertTrue(
                    normalizedSql.contains(fragment.toLowerCase(Locale.ROOT)),
                    () -> "SQL 片段缺失: " + fragment + "，实际 SQL: " + normalizedSql
            );
        }
    }

    private void assertParameterOrder(String statementId, Object parameterObject, String... names) {
        BoundSql boundSql = mappedStatement(statementId).getBoundSql(parameterObject);
        List<String> actualNames = boundSql.getParameterMappings().stream()
                .map(mapping -> mapping.getProperty())
                .toList();
        assertArrayEquals(names, actualNames.toArray(new String[0]));
    }

    private void assertGeneratedKey(String statementId, String keyProperty) {
        String[] keyProperties = mappedStatement(statementId).getKeyProperties();
        assertNotNull(keyProperties);
        assertEquals(1, keyProperties.length);
        assertEquals(keyProperty, keyProperties[0]);
    }

    private MappedStatement mappedStatement(String statementId) {
        return configuration.getMappedStatement(statementId);
    }

    private String normalizeSql(BoundSql boundSql) {
        return boundSql.getSql()
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
