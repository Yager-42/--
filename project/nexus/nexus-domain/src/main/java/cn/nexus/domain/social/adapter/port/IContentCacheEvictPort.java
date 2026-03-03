package cn.nexus.domain.social.adapter.port;

/**
 * Cache eviction port for content detail/read caches.
 */
public interface IContentCacheEvictPort {

    void evictPost(Long postId);
}
