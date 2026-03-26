package cn.nexus.infrastructure.config;

import com.jd.platform.hotkey.client.ClientStarter;
import com.jd.platform.hotkey.client.callback.JdHotKeyStore;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * HotKey 统一桥接层。
 *
 * <p>默认 direct 模式会在当前 JVM 里直接启动客户端；isolated 模式会拉起一个独立 helper 进程，
 * 避免旧版 gRPC/Netty 和主项目 classpath 冲突。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HotKeyStoreBridge {

    private static final String MODE_DIRECT = "direct";
    private static final String MODE_ISOLATED = "isolated";
    private static final Duration HELPER_HTTP_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration HELPER_START_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration HELPER_POLL_INTERVAL = Duration.ofMillis(300);
    private static final String HELPER_HOST = "127.0.0.1";
    private static final String HELPER_CP_RESOURCE = "hotkey-isolated-classpath.txt";

    private final HotKeyProperties hotKeyProperties;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(HELPER_HTTP_TIMEOUT)
            .build();
    private final Object lifecycleMonitor = new Object();

    private volatile Process helperProcess;
    private volatile URI helperBaseUri;
    private volatile String startedMode;

    public void startClient() {
        if (!hotKeyProperties.isEnabled()) {
            return;
        }
        synchronized (lifecycleMonitor) {
            if (startedMode != null) {
                return;
            }
            validateRequiredProperties();
            String mode = normalizeMode(hotKeyProperties.getMode());
            if (MODE_ISOLATED.equals(mode)) {
                startIsolatedHelper();
            } else {
                startDirectClient();
            }
            startedMode = mode;
            log.info(
                    "hotkey client started, mode={}, appName={}, etcdServer={}, pushPeriodMs={}",
                    startedMode,
                    hotKeyProperties.getAppName(),
                    hotKeyProperties.getEtcdServer(),
                    hotKeyProperties.getPushPeriodMs()
            );
        }
    }

    public boolean isHotKey(String key) {
        if (!hotKeyProperties.isEnabled() || !StringUtils.hasText(key)) {
            return false;
        }
        if (startedMode == null) {
            startClient();
        }
        String mode = normalizeMode(startedMode);
        if (MODE_ISOLATED.equals(mode)) {
            return isHotKeyByHelper(key);
        }
        return JdHotKeyStore.isHotKey(key);
    }

    @PreDestroy
    public void shutdown() {
        synchronized (lifecycleMonitor) {
            stopHelperProcess();
            startedMode = null;
        }
    }

    private void startDirectClient() {
        new ClientStarter.Builder()
                .setAppName(hotKeyProperties.getAppName())
                .setEtcdServer(hotKeyProperties.getEtcdServer())
                .setPushPeriod(hotKeyProperties.getPushPeriodMs())
                .build()
                .startPipeline();
    }

    private void startIsolatedHelper() {
        stopHelperProcess();
        int port = chooseFreePort();
        Path logFile = prepareHelperLogFile();
        Process process = launchHelperProcess(port, logFile);
        URI baseUri = URI.create("http://" + HELPER_HOST + ":" + port);
        waitForHelperReady(process, baseUri, logFile);
        helperProcess = process;
        helperBaseUri = baseUri;
    }

    private boolean isHotKeyByHelper(String key) {
        ensureHelperRunning();
        URI baseUri = helperBaseUri;
        if (baseUri == null) {
            return false;
        }
        try {
            String encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/is-hot?key=" + encodedKey))
                    .timeout(HELPER_HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() == 200 && Boolean.parseBoolean(response.body().trim());
        } catch (Exception e) {
            throw new IllegalStateException("hotkey helper request failed", e);
        }
    }

    private void ensureHelperRunning() {
        Process process = helperProcess;
        if (process != null && process.isAlive() && helperBaseUri != null) {
            return;
        }
        synchronized (lifecycleMonitor) {
            Process latest = helperProcess;
            if (latest != null && latest.isAlive() && helperBaseUri != null) {
                return;
            }
            startIsolatedHelper();
        }
    }

    private Process launchHelperProcess(int port, Path logFile) {
        List<String> command = new ArrayList<>();
        command.add(resolveJavaExecutable());
        command.add("-cp");
        command.add(buildHelperClasspath());
        command.add(HotKeyBridgeServer.class.getName());
        command.add("--port=" + port);
        command.add("--appName=" + hotKeyProperties.getAppName());
        command.add("--etcdServer=" + hotKeyProperties.getEtcdServer());
        command.add("--pushPeriodMs=" + hotKeyProperties.getPushPeriodMs());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        try {
            return builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("failed to start hotkey helper process", e);
        }
    }

    private void waitForHelperReady(Process process, URI baseUri, Path logFile) {
        Instant deadline = Instant.now().plus(HELPER_START_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IllegalStateException("hotkey helper exited early: " + tailHelperLog(logFile));
            }
            if (isHelperHealthy(baseUri)) {
                return;
            }
            sleepQuietly();
        }
        stopHelperProcess();
        throw new IllegalStateException("hotkey helper startup timeout: " + tailHelperLog(logFile));
    }

    private boolean isHelperHealthy(URI baseUri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/health"))
                    .timeout(HELPER_HTTP_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() == 200 && "ok".equalsIgnoreCase(response.body().trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String buildHelperClasspath() {
        List<String> classpathEntries = new ArrayList<>();
        classpathEntries.add(resolveCurrentCodeLocation().toString());

        Path mavenRepo = Path.of(System.getProperty("user.home"), ".m2", "repository");
        try (InputStream inputStream = HotKeyStoreBridge.class.getClassLoader().getResourceAsStream(HELPER_CP_RESOURCE)) {
            if (inputStream == null) {
                throw new IllegalStateException("missing resource: " + HELPER_CP_RESOURCE);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    Path jar = mavenRepo.resolve(trimmed.replace('/', java.io.File.separatorChar));
                    if (!Files.exists(jar)) {
                        throw new IllegalStateException("missing helper dependency: " + jar);
                    }
                    classpathEntries.add(jar.toString());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to read helper classpath resource", e);
        }
        return String.join(java.io.File.pathSeparator, classpathEntries);
    }

    private Path resolveCurrentCodeLocation() {
        try {
            return Path.of(HotKeyBridgeServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException("failed to resolve helper code location", e);
        }
    }

    private Path prepareHelperLogFile() {
        try {
            Path dir = Path.of(System.getProperty("java.io.tmpdir"), "nexus-hotkey-helper");
            Files.createDirectories(dir);
            return dir.resolve("helper.log");
        } catch (IOException e) {
            throw new IllegalStateException("failed to prepare hotkey helper log file", e);
        }
    }

    private String tailHelperLog(Path logFile) {
        try {
            if (!Files.exists(logFile)) {
                return "(helper log missing)";
            }
            List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int start = Math.max(0, lines.size() - 20);
            return String.join(" | ", lines.subList(start, lines.size()));
        } catch (Exception e) {
            return "(failed to read helper log: " + e.getMessage() + ")";
        }
    }

    private void stopHelperProcess() {
        helperBaseUri = null;
        Process process = helperProcess;
        helperProcess = null;
        if (process == null) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void validateRequiredProperties() {
        if (!StringUtils.hasText(hotKeyProperties.getAppName())) {
            throw new IllegalStateException("hotkey.appName is required");
        }
        if (!StringUtils.hasText(hotKeyProperties.getEtcdServer())) {
            throw new IllegalStateException("hotkey.etcdServer is required");
        }
        if (hotKeyProperties.getPushPeriodMs() == null || hotKeyProperties.getPushPeriodMs() <= 0) {
            hotKeyProperties.setPushPeriodMs(500L);
        }
    }

    private String normalizeMode(String mode) {
        if (MODE_ISOLATED.equalsIgnoreCase(mode)) {
            return MODE_ISOLATED;
        }
        return MODE_DIRECT;
    }

    private int chooseFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("failed to allocate helper port", e);
        }
    }

    private String resolveJavaExecutable() {
        Path binDir = Path.of(System.getProperty("java.home"), "bin");
        Path javaExe = binDir.resolve(isWindows() ? "java.exe" : "java");
        if (Files.exists(javaExe)) {
            return javaExe.toString();
        }
        return "java";
    }

    private boolean isWindows() {
        String osName = System.getProperty("os.name", "");
        return osName.toLowerCase().contains("win");
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(HELPER_POLL_INTERVAL.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for hotkey helper", e);
        }
    }
}
