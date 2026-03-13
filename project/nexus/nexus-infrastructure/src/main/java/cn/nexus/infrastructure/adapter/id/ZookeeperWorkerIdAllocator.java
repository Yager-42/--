package cn.nexus.infrastructure.adapter.id;

import cn.nexus.infrastructure.config.LeafProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Zookeeper 分配 Snowflake workerId。
 *
 * <p>策略：在固定路径下创建 EPHEMERAL 节点（0..1023），抢占成功即得到 workerId。
 * ZK 断开后临时节点会消失，从而释放 workerId。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ZookeeperWorkerIdAllocator {

    private static final int MAX_WORKER_ID = 1023;

    private final LeafProperties leafProperties;

    private CuratorFramework curator;
    private volatile Integer workerId;

    @PostConstruct
    public void init() throws Exception {
        if (!leafProperties.getSnowflake().isEnabled()) {
            log.info("leaf snowflake disabled, skip zookeeper workerId allocation");
            return;
        }
        String zk = normalize(leafProperties.getSnowflake().getZkAddress());
        if (zk == null) {
            throw new IllegalStateException("leaf.snowflake.zk-address is blank");
        }

        curator = CuratorFrameworkFactory.builder()
                .connectString(zk)
                .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .connectionTimeoutMs((int) TimeUnit.SECONDS.toMillis(10))
                .sessionTimeoutMs((int) TimeUnit.SECONDS.toMillis(30))
                .build();
        curator.start();

        if (!curator.blockUntilConnected(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("zookeeper connect timeout, zk=" + zk);
        }

        ensureRootPath();
        allocateWorkerId();
    }

    public int workerId() {
        Integer id = workerId;
        if (id == null) {
            throw new IllegalStateException("workerId not allocated");
        }
        return id;
    }

    private void ensureRootPath() throws Exception {
        try {
            curator.create().creatingParentsIfNeeded().withMode(CreateMode.PERSISTENT).forPath(rootPath());
        } catch (KeeperException.NodeExistsException ignored) {
            // already exists
        }
    }

    private void allocateWorkerId() throws Exception {
        String payload = buildPayload();
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i <= MAX_WORKER_ID; i++) {
            String path = workerPath(i);
            try {
                curator.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(path, data);
                workerId = i;
                log.info("zookeeper workerId allocated, workerId={}, path={}", i, path);
                return;
            } catch (KeeperException.NodeExistsException ignored) {
                // try next
            }
        }
        throw new IllegalStateException("no available workerId in zookeeper (0..1023)");
    }

    private String buildPayload() {
        Integer port = leafProperties.getSnowflake().getPort();
        String p = port == null ? "" : String.valueOf(port);
        return "allocatedAt=" + Instant.now() + ";port=" + p;
    }

    private String rootPath() {
        return "/snowflake/" + leafName() + "/workers";
    }

    private String workerPath(int workerId) {
        return rootPath() + "/" + workerId;
    }

    private String leafName() {
        String name = normalize(leafProperties.getName());
        return name == null ? "nexus" : name;
    }

    private String normalize(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    @PreDestroy
    public void destroy() {
        if (curator != null) {
            try {
                curator.close();
            } catch (Exception ignored) {
            }
        }
    }
}

