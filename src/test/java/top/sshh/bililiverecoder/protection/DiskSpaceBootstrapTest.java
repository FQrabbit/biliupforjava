package top.sshh.bililiverecoder.protection;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DiskSpaceBootstrapTest {
    @TempDir Path directory;
    DiskSpaceGuard guard;
    static final AtomicInteger initialized = new AtomicInteger();
    @Configuration(proxyBeanMethods = false) static class Application {
        @Bean String databaseLikeInitializer() { initialized.incrementAndGet(); return "unexpected"; }
    }
    @AfterEach void cleanup() { if (guard != null) guard.close(); }

    @Test void lowSpaceStopsBeforeAnyDatabaseOrApplicationBeanInitialization() {
        initialized.set(0);
        var settings = DiskSpaceSettings.from(new MockEnvironment().withProperty("record.work-path", directory.toString()).withProperty("server.port","0"));
        guard = new DiskSpaceGuard(settings, new DiskSpaceProbe() {
            @Override public List<Volume> collect(List<Path> paths) { return List.of(new Volume("work", 100, 0, 100)); }
        }, new DiskProtectionMarker(settings.marker()), System::currentTimeMillis);
        DiskSpaceGuard.install(guard);
        SpringApplication application = new SpringApplication(Application.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.addListeners(new DiskSpaceBootstrap());
        assertThrows(RuntimeException.class, () -> application.run("--record.work-path=" + directory,
                "--server.port=0", "--spring.config.location=optional:file:" + directory.resolve("absent.yml")));
        assertEquals(0, initialized.get()); assertTrue(DiskSpaceGuard.blocksBusiness());
    }

    @Test void aotGenerationDoesNotProbeDisksOrStartProtection() {
        String previous = System.getProperty("spring.aot.processing");
        System.setProperty("spring.aot.processing", "true");
        try {
            var env = new MockEnvironment().withProperty("record.work-path", directory.resolve("missing").toString());
            var event = new org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent(
                    new org.springframework.boot.DefaultBootstrapContext(), new SpringApplication(Application.class), new String[0], env);
            new DiskSpaceBootstrap().onApplicationEvent(event);
            assertNull(DiskSpaceGuard.current()); assertFalse(java.nio.file.Files.exists(directory.resolve("missing")));
        } finally {
            if (previous == null) System.clearProperty("spring.aot.processing"); else System.setProperty("spring.aot.processing", previous);
        }
    }
}
