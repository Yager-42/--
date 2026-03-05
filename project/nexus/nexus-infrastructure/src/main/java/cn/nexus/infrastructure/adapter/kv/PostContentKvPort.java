package cn.nexus.infrastructure.adapter.kv;

import cn.nexus.domain.social.adapter.port.IPostContentKvPort;
import cn.nexus.infrastructure.adapter.kv.cassandra.NoteContentDO;
import cn.nexus.infrastructure.adapter.kv.cassandra.NoteContentRepository;
import cn.nexus.types.enums.ResponseCode;
import cn.nexus.types.exception.AppException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class PostContentKvPort implements IPostContentKvPort {

    private final NoteContentRepository noteContentRepository;

    @Override
    public void add(String uuid, String content) {
        UUID id = parseUuid(uuid, "uuid 不能为空");
        if (content == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "content 不能为空");
        }
        noteContentRepository.save(NoteContentDO.builder().id(id).content(content).build());
    }

    @Override
    public String find(String uuid) {
        UUID id = parseUuid(uuid, "uuid 不能为空");
        Optional<NoteContentDO> optional = noteContentRepository.findById(id);
        if (optional.isEmpty()) {
            throw new AppException(ResponseCode.NOT_FOUND.getCode(), ResponseCode.NOT_FOUND.getInfo());
        }
        return optional.get().getContent();
    }

    @Override
    public Map<String, String> findBatch(List<String> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return Map.of();
        }
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (String raw : uuids) {
            UUID id = tryParseUuid(raw);
            if (id != null) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Iterable<NoteContentDO> rows = noteContentRepository.findAllById(ids);
        Map<String, String> map = new HashMap<>();
        for (NoteContentDO row : rows) {
            if (row == null || row.getId() == null) {
                continue;
            }
            map.put(row.getId().toString(), row.getContent() == null ? "" : row.getContent());
        }
        return map;
    }

    @Override
    public void delete(String uuid) {
        UUID id = tryParseUuid(uuid);
        if (id == null) {
            return;
        }
        noteContentRepository.deleteById(id);
    }

    private UUID parseUuid(String raw, String emptyMsg) {
        String t = normalize(raw);
        if (t == null) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), emptyMsg);
        }
        try {
            return UUID.fromString(t);
        } catch (Exception e) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "uuid 非法");
        }
    }

    private UUID tryParseUuid(String raw) {
        String t = normalize(raw);
        if (t == null) {
            return null;
        }
        try {
            return UUID.fromString(t);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String normalize(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
