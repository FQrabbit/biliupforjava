package top.sshh.bililiverecoder.protection;

import com.sun.net.httpserver.*;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import top.sshh.bililiverecoder.BiliLiveRecordApplication;
import top.sshh.bililiverecoder.config.db.MaintenanceDataSource;

import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.*;

public final class DiskSpaceBootstrap implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {
    private static volatile org.springframework.core.env.PropertyResolver environment;
    static volatile org.springframework.context.ConfigurableApplicationContext runningContext;
    private static final Map<MaintenanceDataSource, DiskSpaceGuard> pools = new ConcurrentHashMap<>();
    public static void track(MaintenanceDataSource source) {
        DiskSpaceGuard guard = DiskSpaceGuard.current();
        if (guard != null) pools.put(source, guard);
    }
    public static boolean poolsClosed() {
        return pools.entrySet().stream().filter(entry -> entry.getValue() == DiskSpaceGuard.current())
                .allMatch(entry -> entry.getKey().isProtectionComplete());
    }

    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }
    @Override public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        // AOT 只生成启动代码，构建时不能探测用户磁盘或启动保护服务
        if (Boolean.getBoolean("spring.aot.processing")) return;
        ConfigurableEnvironment prepared = event.getEnvironment();
        Map<String, Object> ssl = new java.util.HashMap<>();
        for (String key : new String[]{"enabled", "key-store", "key-store-password", "key-store-type", "key-password",
                "key-alias", "trust-store", "trust-store-password", "trust-store-type", "protocol", "client-auth",
                "enabled-protocols", "ciphers", "certificate"}) {
            String value = prepared.getProperty("server.ssl." + key);
            if (value != null) ssl.put("server.ssl." + key, value);
        }
        var sources = new org.springframework.core.env.MutablePropertySources();
        sources.addFirst(new org.springframework.core.env.MapPropertySource("diskProtectionSsl", ssl));
        environment = new org.springframework.core.env.PropertySourcesPropertyResolver(sources);
        DiskSpaceGuard guard = DiskSpaceGuard.current();
        DiskSpaceSettings settings = DiskSpaceSettings.from(prepared);
        if (guard == null || !guard.settings().equals(settings)) {
            if (guard != null) {
                if (DiskSpaceGuard.blocksBusiness()) throw new IllegalStateException("保护期间不能通过更换配置绕过恢复检查");
                guard.close();
            }
            long origin = System.nanoTime();
            guard = new DiskSpaceGuard(settings, new DiskSpaceProbe(settings.historyFile()), new DiskProtectionMarker(settings.marker()),
                    () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - origin));
            DiskSpaceGuard.install(guard);
            guard.initialize(); guard.startMonitor();
        } else { guard.poll(); }
        pools.entrySet().removeIf(entry -> entry.getKey().isProtectionComplete() || entry.getValue() != DiskSpaceGuard.current());
        if (DiskSpaceGuard.blocksConnections()) throw new StartupProtectionException();
    }

    public static void run(String[] args) {
        while (true) {
            org.springframework.context.ConfigurableApplicationContext context = null;
            try {
                context = BiliLiveRecordApplication.createSpringApplication().run(args);
                context.getBean(DiskSpaceRuntime.class).activate();
                return;
            }
            catch (RuntimeException failure) {
                DiskSpaceGuard guard = DiskSpaceGuard.current();
                if (guard == null || !DiskSpaceGuard.blocksBusiness()) throw failure;
                boolean saved = guard.saveProtection();
                if (context != null) context.close();
                if (!poolsClosed()) {
                    guard.failed(new IllegalStateException("启动失败后仍有数据库连接池未关闭，不能自动重开数据库", failure));
                } else if (hasStartupProtection(failure)) {
                    guard.paused(saved);
                } else { guard.failed(failure); }
                try { serveUntilResume(guard); }
                catch (Exception serverFailure) { guard.failed(serverFailure); throw new IllegalStateException("无法启动磁盘保护页", serverFailure); }
            }
            catch (Exception failure) {
                DiskSpaceGuard guard = DiskSpaceGuard.current();
                if (guard == null) throw new IllegalStateException(failure);
                guard.protect("业务启动校验失败");
                guard.saveProtection();
                if (context != null) context.close();
                guard.failed(failure);
                try { serveUntilResume(guard); }
                catch (Exception serverFailure) { throw new IllegalStateException("无法启动磁盘保护页", serverFailure); }
            }
        }
    }

    private static boolean hasStartupProtection(Throwable failure) {
        for (Throwable next = failure; next != null; next = next.getCause()) if (next instanceof StartupProtectionException) return true;
        return false;
    }

    public static void serveUntilResume(DiskSpaceGuard guard) throws Exception {
        CountDownLatch resume = new CountDownLatch(1);
        guard.attach(null, () -> {
            try {
                for (var entry : pools.entrySet()) {
                    if (entry.getValue() == guard && !entry.getKey().isProtectionComplete()) entry.getKey().retryProtectionShutdown();
                }
                if (!poolsClosed()) throw new IllegalStateException("仍有数据库连接池未关闭，请先检查关闭失败的原因");
                guard.paused(guard.saveProtection());
                guard.beginRecovery(); resume.countDown();
            } catch (Exception failure) { guard.failed(failure); }
        }, null);
        HttpServer server = createServer(guard.settings());
        ExecutorService requests = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), r -> DiskSpaceGuard.daemon(r, "disk-protection-http"), new ThreadPoolExecutor.AbortPolicy());
        server.setExecutor(requests);
        String context = guard.settings().contextPath();
        server.createContext(context.isEmpty() ? "/" : context + "/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath().substring(context.length());
                DiskProtectionHttp.Response response = DiskProtectionHttp.handle(guard, exchange.getRequestMethod(), path,
                        exchange.getRequestHeaders().getFirst("Authorization"), exchange.getRequestHeaders().getFirst("X-Protection-Token"),
                        exchange.getRequestHeaders().getFirst("Accept"));
                exchange.getResponseHeaders().set("Content-Type", response.type());
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                if (response.status() == 503) exchange.getResponseHeaders().set("Retry-After", "60");
                if (response.challenge()) exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"biliupforjava\", charset=\"UTF-8\"");
                exchange.sendResponseHeaders(response.status(), response.body().length);
                exchange.getResponseBody().write(response.body());
            } finally { exchange.close(); }
        });
        try {
            server.start();
            System.err.println("磁盘保护页已启动，端口：" + server.getAddress().getPort() + "，请检查空间后手动恢复");
            resume.await();
        } finally { server.stop(1); requests.shutdownNow(); guard.detach(); }
    }

    private static HttpServer createServer(DiskSpaceSettings settings) throws Exception {
        InetSocketAddress bind = new InetSocketAddress(settings.address(), settings.port());
        if (environment == null || !environment.getProperty("server.ssl.enabled", Boolean.class,
                environment.containsProperty("server.ssl.key-store") || environment.containsProperty("server.ssl.certificate"))) {
            return HttpServer.create(bind, 32);
        }
        String location = environment.getRequiredProperty("server.ssl.key-store");
        char[] password = environment.getProperty("server.ssl.key-store-password", "").toCharArray();
        KeyStore keys = KeyStore.getInstance(environment.getProperty("server.ssl.key-store-type", "JKS"));
        try (var input = new DefaultResourceLoader().getResource(location).getInputStream()) { keys.load(input, password); }
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        char[] keyPassword = environment.getProperty("server.ssl.key-password", new String(password)).toCharArray();
        String alias = environment.getProperty("server.ssl.key-alias");
        if (alias != null) {
            KeyStore selected = KeyStore.getInstance(keys.getType()); selected.load(null, password);
            selected.setKeyEntry(alias, keys.getKey(alias, keyPassword), keyPassword, keys.getCertificateChain(alias));
            keys = selected;
        }
        manager.init(keys, keyPassword);
        TrustManager[] trustManagers = null;
        if (environment.containsProperty("server.ssl.trust-store")) {
            KeyStore trusted = KeyStore.getInstance(environment.getProperty("server.ssl.trust-store-type", "JKS"));
            try (var input = new DefaultResourceLoader().getResource(environment.getRequiredProperty("server.ssl.trust-store")).getInputStream()) {
                trusted.load(input, environment.getProperty("server.ssl.trust-store-password", "").toCharArray());
            }
            TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trust.init(trusted); trustManagers = trust.getTrustManagers();
        }
        SSLContext ssl = SSLContext.getInstance(environment.getProperty("server.ssl.protocol", "TLS"));
        ssl.init(manager.getKeyManagers(), trustManagers, null);
        HttpsServer server = HttpsServer.create(bind, 32);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl) {
            @Override public void configure(HttpsParameters parameters) {
                SSLParameters options = getSSLContext().getDefaultSSLParameters();
                String clientAuth = environment.getProperty("server.ssl.client-auth", "none");
                if (clientAuth.equalsIgnoreCase("need")) options.setNeedClientAuth(true);
                else if (clientAuth.equalsIgnoreCase("want")) options.setWantClientAuth(true);
                String protocols = environment.getProperty("server.ssl.enabled-protocols");
                String ciphers = environment.getProperty("server.ssl.ciphers");
                if (protocols != null) options.setProtocols(protocols.split("\\s*,\\s*"));
                if (ciphers != null) options.setCipherSuites(ciphers.split("\\s*,\\s*"));
                parameters.setSSLParameters(options);
            }
        });
        return server;
    }

    private static final class StartupProtectionException extends RuntimeException {
        StartupProtectionException() { super("磁盘空间保护已启用，业务数据库未启动"); }
    }
}
