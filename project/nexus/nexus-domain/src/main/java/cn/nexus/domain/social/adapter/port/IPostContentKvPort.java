package cn.nexus.domain.social.adapter.port;

import java.util.List;
import java.util.Map;

/**
 * Post content KV port.
 */
public interface IPostContentKvPort {

    void add(String uuid, String content);

    String find(String uuid);

    Map<String, String> findBatch(List<String> uuids);

    void delete(String uuid);
}
