package cn.nexus.infrastructure.adapter.user.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import cn.nexus.domain.user.model.valobj.UserProfileVO;
import cn.nexus.infrastructure.dao.social.IUserBaseDao;
import cn.nexus.infrastructure.dao.social.po.UserBasePO;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.RedisOperations;

class UserProfileRepositoryTest {

    @Test
    void get_shouldMapUserBaseToProfileVo() {
        IUserBaseDao dao = Mockito.mock(IUserBaseDao.class);
        RedisOperations<String, String> redisOps = Mockito.mock(RedisOperations.class);
        UserProfileRepository repo = new UserProfileRepository(dao, redisOps);

        UserBasePO po = new UserBasePO();
        po.setUserId(1L);
        po.setUsername("u1");
        po.setNickname("n1");
        po.setAvatarUrl("a1");
        when(dao.selectByUserId(1L)).thenReturn(po);

        UserProfileVO vo = repo.get(1L);
        assertNotNull(vo);
        assertEquals(1L, vo.getUserId());
        assertEquals("u1", vo.getUsername());
        assertEquals("n1", vo.getNickname());
        assertEquals("a1", vo.getAvatarUrl());
    }

    @Test
    void updatePatch_updatedRowsPositive_shouldReturnTrue() {
        IUserBaseDao dao = Mockito.mock(IUserBaseDao.class);
        RedisOperations<String, String> redisOps = Mockito.mock(RedisOperations.class);
        UserProfileRepository repo = new UserProfileRepository(dao, redisOps);

        when(dao.updatePatch(1L, "n2", null)).thenReturn(1);
        assertTrue(repo.updatePatch(1L, "n2", null));
        verify(redisOps).delete("social:userbase:1");
    }

    @Test
    void updatePatch_rowsZeroButExists_shouldReturnTrue() {
        IUserBaseDao dao = Mockito.mock(IUserBaseDao.class);
        RedisOperations<String, String> redisOps = Mockito.mock(RedisOperations.class);
        UserProfileRepository repo = new UserProfileRepository(dao, redisOps);

        when(dao.updatePatch(1L, "n2", null)).thenReturn(0);
        when(dao.selectByUserId(1L)).thenReturn(new UserBasePO());
        assertTrue(repo.updatePatch(1L, "n2", null));
        verify(redisOps, never()).delete(Mockito.anyString());
    }

    @Test
    void updatePatch_rowsZeroAndNotExists_shouldReturnFalse() {
        IUserBaseDao dao = Mockito.mock(IUserBaseDao.class);
        RedisOperations<String, String> redisOps = Mockito.mock(RedisOperations.class);
        UserProfileRepository repo = new UserProfileRepository(dao, redisOps);

        when(dao.updatePatch(1L, "n2", null)).thenReturn(0);
        when(dao.selectByUserId(1L)).thenReturn(null);
        assertFalse(repo.updatePatch(1L, "n2", null));
        verify(redisOps, never()).delete(Mockito.anyString());
    }
}
