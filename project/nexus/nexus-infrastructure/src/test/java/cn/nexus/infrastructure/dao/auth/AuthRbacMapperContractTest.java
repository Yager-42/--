package cn.nexus.infrastructure.dao.auth;

import cn.nexus.infrastructure.dao.auth.po.AuthUserRolePO;
import java.io.IOException;
import java.io.InputStream;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthRbacMapperContractTest {

    private static Configuration configuration;

    @BeforeAll
    static void setUpConfiguration() throws Exception {
        configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(IAuthRoleDao.class);
        configuration.addMapper(IAuthUserRoleDao.class);
        parseMapper("mapper/auth/AuthRoleMapper.xml");
        parseMapper("mapper/auth/AuthUserRoleMapper.xml");
    }

    @Test
    void authRoleMapper_shouldSupportFindByRoleCode() {
        Map<String, Object> param = Map.of("roleCode", "ADMIN");
        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthRoleDao.selectByRoleCode",
                param,
                "from auth_role",
                "where role_code = ?"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthRoleDao.selectByRoleCode",
                param,
                "roleCode"
        );
    }

    @Test
    void authUserRoleMapper_shouldSupportListInsertIgnoreAndCountByRoleCode() {
        Map<String, Object> listParam = Map.of("userId", 1001L);
        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao.selectRoleCodesByUserId",
                listParam,
                "select r.role_code",
                "from auth_user_role ur",
                "inner join auth_role r on r.role_id = ur.role_id",
                "where ur.user_id = ?",
                "order by ur.id asc"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao.selectRoleCodesByUserId",
                listParam,
                "userId"
        );

        Map<String, Object> roleUserParam = Map.of("roleCode", "ADMIN");
        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao.selectUserIdsByRoleCode",
                roleUserParam,
                "select ur.user_id",
                "from auth_user_role ur",
                "inner join auth_role r on r.role_id = ur.role_id",
                "where r.role_code = ?",
                "order by ur.id asc"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao.selectUserIdsByRoleCode",
                roleUserParam,
                "roleCode"
        );

        AuthUserRolePO po = new AuthUserRolePO();
        po.setUserId(1001L);
        po.setId(9001L);
        po.setRoleId(2L);
        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao.insertIgnore",
                po,
                "insert ignore into auth_user_role",
                "id",
                "user_id",
                "role_id",
                "create_time",
                "now()"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao.insertIgnore",
                po,
                "id",
                "userId",
                "roleId"
        );

        Map<String, Object> deleteParam = Map.of(
                "userId", 1001L,
                "roleCode", "ADMIN"
        );
        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao.deleteByUserIdAndRoleCode",
                deleteParam,
                "delete ur",
                "from auth_user_role ur",
                "inner join auth_role r on r.role_id = ur.role_id",
                "where ur.user_id = ?",
                "and r.role_code = ?"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao.deleteByUserIdAndRoleCode",
                deleteParam,
                "userId",
                "roleCode"
        );

        Map<String, Object> countParam = Map.of(
                "userId", 1001L,
                "roleCode", "ADMIN"
        );
        assertSqlContains(
                "cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao.countByUserIdAndRoleCode",
                countParam,
                "select count(1)",
                "from auth_user_role ur",
                "inner join auth_role r on r.role_id = ur.role_id",
                "where ur.user_id = ?",
                "and r.role_code = ?"
        );
        assertParameterOrder(
                "cn.nexus.infrastructure.dao.auth.IAuthUserRoleDao.countByUserIdAndRoleCode",
                countParam,
                "userId",
                "roleCode"
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
