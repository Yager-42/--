package cn.nexus.infrastructure.adapter.kv;

import cn.nexus.domain.social.adapter.port.IPostContentKvPort;
import cn.nexus.infrastructure.dao.kv.IPostContentDao;
import cn.nexus.infrastructure.dao.kv.po.PostContentPO;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PostContentKvPort implements IPostContentKvPort {

    private final IPostContentDao postContentDao;

    @Override
    public void add(String uuid, String content) {
        String id = normalize(uuid);
        if (id == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "uuid 不能为空");
        }
        if (content == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "content 不能为空");
        }
        postContentDao.upsert(id, content);
    }

    @Override
    public String find(String uuid) {
        String id = normalize(uuid);
        if (id == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "uuid 不能为空");
        }
        PostContentPO po = postContentDao.selectByUuid(id);
        if (po == null) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }
        return po.getContent();
    }

    @Override
    public void delete(String uuid) {
        String id = normalize(uuid);
        if (id == null) {
            return;
        }
        postContentDao.deleteByUuid(id);
    }

    private String normalize(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
