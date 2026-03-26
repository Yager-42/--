package cn.nexus.infrastructure.config;

import com.jd.platform.hotkey.client.ClientStarter;
import com.jd.platform.hotkey.client.callback.JdHotKeyStore;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HotKey 隔离 helper 进程。
 *
 * <p>这个进程只做一件事：在干净 classpath 里启动 jd-hotkey-client，并通过本地 HTTP 暴露查询接口。</p>
 */
public final class HotKeyBridgeServer {

    private HotKeyBridgeServer() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parseArgs(args);
        int port = parseRequiredInt(options, "port");
        String appName = require(options, "appName");
        String etcdServer = require(options, "etcdServer");
        long pushPeriodMs = parseLong(options.getOrDefault("pushPeriodMs", "500"), 500L);

        new ClientStarter.Builder()
                .setAppName(appName)
                .setEtcdServer(etcdServer)
                .setPushPeriod(pushPeriodMs)
                .build()
                .startPipeline();

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r);
            thread.setName("hotkey-helper-http");
            thread.setDaemon(false);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/health", exchange -> writeText(exchange, 200, "ok"));
        server.createContext("/is-hot", exchange -> handleIsHot(exchange));

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            executor.shutdownNow();
            latch.countDown();
        }));

        server.start();
        System.out.println("hotkey helper listening on 127.0.0.1:" + port);
        latch.await();
    }

    private static void handleIsHot(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeText(exchange, 405, "method-not-allowed");
            return;
        }
        String key = query(exchange.getRequestURI().getRawQuery()).get("key");
        if (key == null || key.isBlank()) {
            writeText(exchange, 200, "false");
            return;
        }
        boolean hot = JdHotKeyStore.isHotKey(key);
        writeText(exchange, 200, Boolean.toString(hot));
    }

    private static void writeText(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> values = new HashMap<>();
        if (args == null) {
            return values;
        }
        for (String arg : args) {
            if (arg == null || !arg.startsWith("--")) {
                continue;
            }
            int split = arg.indexOf('=');
            if (split <= 2 || split >= arg.length() - 1) {
                continue;
            }
            values.put(arg.substring(2, split), arg.substring(split + 1));
        }
        return values;
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> values = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            int split = pair.indexOf('=');
            if (split <= 0) {
                continue;
            }
            String key = decode(pair.substring(0, split));
            String value = decode(pair.substring(split + 1));
            values.put(key, value);
        }
        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String require(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required argument: " + key);
        }
        return value;
    }

    private static int parseRequiredInt(Map<String, String> values, String key) {
        String value = require(values, key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid integer argument: " + key + "=" + value, e);
        }
    }

    private static long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
