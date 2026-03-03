package cn.nexus.domain.social.adapter.port;

/**
 * Post content KV port.
 */
public interface IPostContentKvPort {

    void add(String uuid, String content);

    String find(String uuid);

    void delete(String uuid);
}
