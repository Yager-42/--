package cn.nexus.infrastructure.adapter.social.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.nexus.domain.social.model.valobj.UserBriefVO;
import cn.nexus.infrastructure.dao.social.IUserBaseDao;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.ValueOperations;

class UserBaseRepositoryTest {

    @Test
    void listByUserIds_shouldPreferNicknameAndFallbackToUsername() {
        IUserBaseDao dao = Mockito.mock(IUserBaseDao.class);
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        // 模拟缓存 miss，走 DB 回源
        when(ops.multiGet(any())).thenReturn(java.util.Arrays.asList(null, null));
        UserBaseRepository repo = new UserBaseRepository(dao, ops, new ObjectMapper());

        UserBasePO po1 = new UserBasePO();
        po1.setUserId(1L);
        po1.setUsername("user1");
        po1.setNickname("nick1");
        po1.setAvatarUrl("a1");

        UserBasePO po2 = new UserBasePO();
        po2.setUserId(2L);
        po2.setUsername("User2");
        po2.setNickname("");
        po2.setAvatarUrl("a2");

        UserBasePO po3 = new UserBasePO();
        po3.setUserId(null);
        po3.setUsername("ignored");
        po3.setNickname("ignored");
        po3.setAvatarUrl("ignored");

        when(dao.selectByUserIds(List.of(1L, 2L))).thenReturn(List.of(po1, po2, po3));

        List<UserBriefVO> res = repo.listByUserIds(List.of(1L, 2L));
        assertEquals(2, res.size());

        assertEquals(1L, res.get(0).getUserId());
        assertEquals("nick1", res.get(0).getNickname());
        assertEquals("a1", res.get(0).getAvatarUrl());

        assertEquals(2L, res.get(1).getUserId());
        assertEquals("User2", res.get(1).getNickname());
        assertEquals("a2", res.get(1).getAvatarUrl());
    }

    @Test
    void listByUserIds_emptyInput_shouldReturnEmptyList() {
        IUserBaseDao dao = Mockito.mock(IUserBaseDao.class);
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);
        UserBaseRepository repo = new UserBaseRepository(dao, ops, new ObjectMapper());

        assertTrue(repo.listByUserIds(null).isEmpty());
        assertTrue(repo.listByUserIds(List.of()).isEmpty());
    }

    @Test
    void listByUserIds_cacheHit_shouldNotQueryDb() {
        IUserBaseDao dao = Mockito.mock(IUserBaseDao.class);
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);

        String j1 = "{\"userId\":1,\"nickname\":\"n1\",\"avatarUrl\":\"a1\"}";
        String j2 = "{\"userId\":2,\"nickname\":\"n2\",\"avatarUrl\":\"a2\"}";
        when(ops.multiGet(List.of("social:userbase:1", "social:userbase:2"))).thenReturn(List.of(j1, j2));

        UserBaseRepository repo = new UserBaseRepository(dao, ops, new ObjectMapper());
        List<UserBriefVO> res = repo.listByUserIds(List.of(1L, 2L));
        assertEquals(2, res.size());
        assertEquals("n1", res.get(0).getNickname());
        assertEquals("n2", res.get(1).getNickname());
        verify(dao, never()).selectByUserIds(any());
    }

    @Test
    void listByUserIds_partialHit_shouldQueryDbForMissAndBackfill() {
        IUserBaseDao dao = Mockito.mock(IUserBaseDao.class);
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);

        String j1 = "{\"userId\":1,\"nickname\":\"n1\",\"avatarUrl\":\"a1\"}";
        when(ops.multiGet(List.of("social:userbase:1", "social:userbase:2"))).thenReturn(java.util.Arrays.asList(j1, null));

        UserBasePO po2 = new UserBasePO();
        po2.setUserId(2L);
        po2.setUsername("u2");
        po2.setNickname("n2");
        po2.setAvatarUrl("a2");
        when(dao.selectByUserIds(List.of(2L))).thenReturn(List.of(po2));

        UserBaseRepository repo = new UserBaseRepository(dao, ops, new ObjectMapper());
        List<UserBriefVO> res = repo.listByUserIds(List.of(1L, 2L));
        assertEquals(2, res.size());
        assertEquals("n1", res.get(0).getNickname());
        assertEquals("n2", res.get(1).getNickname());
        verify(dao).selectByUserIds(List.of(2L));
        verify(ops).set(eq("social:userbase:2"), anyString(), eq(3600L), eq(TimeUnit.SECONDS));
    }

    @Test
    void listByUsernames_cacheMiss_shouldQueryDbAndBackfillMappings() {
        IUserBaseDao dao = Mockito.mock(IUserBaseDao.class);
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);

        when(ops.multiGet(List.of("social:userbase:uid:u1"))).thenReturn(java.util.Collections.singletonList(null));

        UserBasePO po = new UserBasePO();
        po.setUserId(1L);
        po.setUsername("u1");
        po.setNickname("n1");
        po.setAvatarUrl("a1");
        when(dao.selectByUsernames(List.of("u1"))).thenReturn(List.of(po));

        UserBaseRepository repo = new UserBaseRepository(dao, ops, new ObjectMapper());
        List<UserBriefVO> res = repo.listByUsernames(List.of("u1"));
        assertEquals(1, res.size());
        assertEquals(1L, res.get(0).getUserId());

        verify(dao).selectByUsernames(List.of("u1"));
        verify(ops).set(eq("social:userbase:uid:u1"), eq("1"), eq(3600L), eq(TimeUnit.SECONDS));
        verify(ops).set(eq("social:userbase:1"), anyString(), eq(3600L), eq(TimeUnit.SECONDS));
    }

    @Test
    void listByUsernames_mappingHitAndBriefHit_shouldNotQueryDb() {
        IUserBaseDao dao = Mockito.mock(IUserBaseDao.class);
        ValueOperations<String, String> ops = Mockito.mock(ValueOperations.class);

        when(ops.multiGet(List.of("social:userbase:uid:u1"))).thenReturn(List.of("1"));
        String j1 = "{\"userId\":1,\"nickname\":\"n1\",\"avatarUrl\":\"a1\"}";
        when(ops.multiGet(List.of("social:userbase:1"))).thenReturn(List.of(j1));

        UserBaseRepository repo = new UserBaseRepository(dao, ops, new ObjectMapper());
        List<UserBriefVO> res = repo.listByUsernames(List.of("u1"));
        assertEquals(1, res.size());
        assertEquals("n1", res.get(0).getNickname());
        verify(dao, never()).selectByUsernames(any());
        verify(dao, never()).selectByUserIds(any());
    }
}
