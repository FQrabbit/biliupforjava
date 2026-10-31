package top.sshh.bililiverecoder.protection;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import top.sshh.bililiverecoder.service.CoreRestartService;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class DiskSpaceProtectionFlowTest {
    public static class OnlyProduction implements org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {
        @Override public void initialize(org.springframework.context.ConfigurableApplicationContext context) {
            context.getBeanFactory().registerSingleton("flowTestTypeExclusion", new org.springframework.boot.context.TypeExcludeFilter() {
                @Override public boolean match(org.springframework.core.type.classreading.MetadataReader reader,
                        org.springframework.core.type.classreading.MetadataReaderFactory factory) {
                    String name = reader.getClassMetadata().getClassName();
                    return name.contains("Test$") || name.endsWith("Test");
                }
            });
        }
    }
    @TempDir Path directory;
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void realApplicationStopsServesIndependentPageAndRestartsOnlyAfterManualRecovery(boolean initiallyLow) throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        String url = "jdbc:h2:" + directory.resolve("db").toString().replace('\\', '/');
        String[] args = {"--record.work-path=" + directory, "--server.port=" + port, "--server.address=127.0.0.1",
                "--record.userName=user", "--record.password=pass", "--spring.datasource.hikari.jdbc-url=" + url,
                "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.jpa.hibernate.ddl-auto=update",
                "--bili.app-key=test", "--bili.app-secret=test", "--record.disk-protection.stable-ms=200",
                "--record.disk-protection.interval-ms=100", "--record.disk-protection.drain-ms=5000",
                "--logging.file.path=" + directory.resolve("log"), "--logging.level.root=WARN",
                "--logging.level.top.sshh.bililiverecoder=WARN",
                "--context.initializer.classes=" + OnlyProduction.class.getName(),
                "--spring.config.location=optional:file:" + directory.resolve("absent.yml")};
        var env = new MockEnvironment().withProperty("record.work-path", directory.toString()).withProperty("server.port", "" + port)
                .withProperty("server.address", "127.0.0.1").withProperty("record.userName", "user").withProperty("record.password", "pass")
                .withProperty("spring.datasource.hikari.jdbc-url", url).withProperty("record.disk-protection.stable-ms", "200")
                .withProperty("record.disk-protection.interval-ms", "100").withProperty("record.disk-protection.drain-ms", "5000");
        var settings = DiskSpaceSettings.from(env);
        AtomicLong free = new AtomicLong((initiallyLow ? 2 : 20) * DiskSpaceSettings.GIB);
        var guard = new DiskSpaceGuard(settings, new DiskSpaceProbe() {
            @Override public List<Volume> collect(List<Path> paths) {
                long total = 100 * DiskSpaceSettings.GIB, available = free.get();
                return List.of(new Volume(directory.toString(), total, available, (total - available) * 100d / total));
            }
        }, new DiskProtectionMarker(settings.marker()), System::currentTimeMillis);
        DiskSpaceGuard.install(guard); guard.initialize(); guard.startMonitor(); CoreRestartService.setApplicationArgs(args);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        String auth = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        URI status = URI.create("http://127.0.0.1:" + port + "/api/system/disk-protection");
        var starter = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            if (initiallyLow) {
                var starting = starter.submit(() -> DiskSpaceBootstrap.run(args));
                await(() -> {
                    try { return request(client, status, auth).statusCode() == 200; } catch (Exception ignored) { return false; }
                }, 10000);
                assertEquals(DiskSpaceGuard.Phase.PAUSED, guard.phase());
                assertFalse(Files.exists(directory.resolve("db.mv.db")), "启动保护页不能先创建业务数据库");
                free.set(20 * DiskSpaceSettings.GIB); await(guard::canResume, 5000);
                var initialRestore = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/system/disk-protection/resume"))
                        .header("Authorization", auth).header("X-Protection-Token", guard.csrfToken())
                        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(202, initialRestore.statusCode());
                starting.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } else { DiskSpaceBootstrap.run(args); }
            assertNotNull(DiskSpaceBootstrap.runningContext);
            assertEquals(DiskSpaceGuard.Phase.RUNNING, guard.phase());
            free.set(2 * DiskSpaceSettings.GIB); guard.poll();
            await(() -> !DiskSpaceBootstrap.runningContext.isActive(), 15000);
            await(() -> {
                try { return request(client, status, auth).statusCode() == 200; } catch (Exception ignored) { return false; }
            }, 10000);
            var response = request(client, status, auth);
            assertEquals("PAUSED", JSON.parseObject(response.body()).getString("phase"));
            assertTrue(JSON.parseObject(response.body()).getBooleanValue("databaseClosed"));
            assertEquals(401, request(client, status, null).statusCode());
            var rejected = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/recordWebHook"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}" )).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(503, rejected.statusCode());
            assertTrue(Files.exists(settings.marker()));
            free.set(20 * DiskSpaceSettings.GIB);
            await(guard::canResume, 5000);
            assertEquals(DiskSpaceGuard.Phase.PAUSED, guard.phase());
            var restore = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/system/disk-protection/resume"))
                    .header("Authorization", auth).header("X-Protection-Token", guard.csrfToken())
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, restore.statusCode());
            await(() -> guard.phase() == DiskSpaceGuard.Phase.RUNNING && DiskSpaceBootstrap.runningContext.isActive(), 30000);
            assertFalse(Files.exists(settings.marker()));
        } finally {
            if (guard.phase() != DiskSpaceGuard.Phase.RUNNING) System.err.println("flow status: " + guard.status());
            guard.close();
            if (DiskSpaceBootstrap.runningContext != null) DiskSpaceBootstrap.runningContext.close();
            starter.shutdownNow();
            ((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory()).stop();
        }
    }

    private static HttpResponse<String> request(HttpClient client, URI uri, String auth) throws Exception {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET();
        if (auth != null) builder.header("Authorization", auth);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    private static void await(BooleanSupplier condition, long timeout) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMillis(timeout).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "等待保护状态切换超时");
    }
}
