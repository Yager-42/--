package cn.nexus.infrastructure.dao.auth;

import cn.nexus.infrastructure.dao.auth.po.AuthAccountPO;
import cn.nexus.infrastructure.dao.auth.po.AuthSmsCodePO;
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
        configuration.addMapper(IAuthSmsCodeDao.class);
        parseMapper("mapper/auth/AuthAccountMapper.xml");
        parseMapper("mapper/auth/AuthSmsCodeMapper.xml");
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

    @Test
    void authSmsCodeMapper_shouldSupportInsertInvalidatePreviousFindLatestAndMarkUsed() {
        AuthSmsCodePO smsCode = new AuthSmsCodePO();
        smsCode.setBizType("REGISTER");
        smsCode.setPhone("13800000002");
        smsCode.setCodeHash("code-hash-v1");
        smsCode.setExpireAt(new Date());
        smsCode.setVerifyFailCount(0);
        smsCode.setSendStatus("SENT");
        smsCode.setRequestIp("127.0.0.1");
        smsCode.setLatestFlag(1);

        Map<String, Object> invalidateParam = Map.of(
                "phone", "13800000002",
                "bizType", "REGISTER"
        );
        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthSmsCodeDao.invalidateLatest",
                invalidateParam,
                "update auth_sms_code",
                "set latest_flag = 0",
                "where phone = ?",
                "and biz_type = ?",
                "and latest_flag = 1"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthSmsCodeDao.invalidateLatest",
                invalidateParam,
                "phone",
                "bizType"
        );

        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthSmsCodeDao.insert",
                smsCode,
                "insert into auth_sms_code",
                "biz_type",
                "phone",
                "code_hash",
                "expire_at",
                "used_at",
                "verify_fail_count",
                "send_status",
                "request_ip",
                "latest_flag",
                "now()"
        );
        assertGeneratedKey(
                "cn.nexus.infrastructure.dao.auth.IAuthSmsCodeDao.insert",
                "id"
        );

        Map<String, Object> selectLatestParam = Map.of(
                "phone", "13800000002",
                "bizType", "REGISTER"
        );
        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthSmsCodeDao.selectLatestActive",
                selectLatestParam,
                "select id, biz_type, phone, code_hash, expire_at, used_at, verify_fail_count, send_status, request_ip, latest_flag, create_time",
                "from auth_sms_code",
                "where phone = ?",
                "and biz_type = ?",
                "and latest_flag = 1",
                "and used_at is null",
                "order by id desc",
                "limit 1"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthSmsCodeDao.selectLatestActive",
                selectLatestParam,
                "phone",
                "bizType"
        );

        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthSmsCodeDao.incrementVerifyFail",
                Map.of("id", 99L),
                "update auth_sms_code",
                "set verify_fail_count = verify_fail_count + 1",
                "where id = ?"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthSmsCodeDao.incrementVerifyFail",
                Map.of("id", 99L),
                "id"
        );

        Map<String, Object> markUsedParam = Map.of(
                "id", 99L,
                "usedAt", new Date()
        );
        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthSmsCodeDao.markUsed",
                markUsedParam,
                "update auth_sms_code",
                "set used_at = ?",
                "latest_flag = 0",
                "where id = ?"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthSmsCodeDao.markUsed",
                markUsedParam,
                "usedAt",
                "id"
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
