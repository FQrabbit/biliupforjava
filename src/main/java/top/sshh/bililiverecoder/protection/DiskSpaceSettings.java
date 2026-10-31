package top.sshh.bililiverecoder.protection;

import org.springframework.core.env.Environment;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record DiskSpaceSettings(Path workPath, List<Path> paths, Path historyFile, double stopPercent, long stopBytes,
        double resumePercent, long resumeBytes, long intervalMillis, long stableMillis,
        long probeTimeoutMillis, long drainMillis, String address, int port, String contextPath,
        String userName, String password) {
    public static final long GIB = 1024L * 1024 * 1024;

    public static DiskSpaceSettings from(Environment env) {
        Path work = path(env.getProperty("record.work-path", "."));
        List<Path> paths = new ArrayList<>();
        paths.add(work);
        Path database = localDatabasePath(env.getProperty("spring.datasource.hikari.jdbc-url",
                env.getProperty("spring.datasource.url", "")));
        if (database != null && !paths.contains(database.getParent())) paths.add(database.getParent());
        String prefix = "record.disk-protection.";
        Path history = path(env.getProperty(prefix + "history-file", ".biliup-storage-"
                + java.util.UUID.nameUUIDFromBytes(paths.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".properties"));
        DiskSpaceSettings settings = new DiskSpaceSettings(work, List.copyOf(paths), history,
                env.getProperty(prefix + "stop-percent", Double.class, 98d),
                env.getProperty(prefix + "stop-bytes", Long.class, 2 * GIB),
                env.getProperty(prefix + "resume-percent", Double.class, 95d),
                env.getProperty(prefix + "resume-bytes", Long.class, 10 * GIB),
                env.getProperty(prefix + "interval-ms", Long.class, 1000L),
                env.getProperty(prefix + "stable-ms", Long.class, 30000L),
                env.getProperty(prefix + "probe-timeout-ms", Long.class, 800L),
                env.getProperty(prefix + "drain-ms", Long.class, 30000L),
                env.getProperty("server.address", "0.0.0.0"), env.getProperty("server.port", Integer.class, 8080),
                env.getProperty("server.servlet.context-path", "").replaceAll("/$", ""),
                env.getProperty("record.userName", ""), env.getProperty("record.password", ""));
        settings.validate();
        return settings;
    }

    public void validate() {
        if (!Double.isFinite(stopPercent) || !Double.isFinite(resumePercent)
                || stopPercent <= 0 || stopPercent > 100 || resumePercent < 0 || resumePercent >= stopPercent
                || stopBytes < 0 || resumeBytes <= stopBytes || intervalMillis < 100 || stableMillis < 0
                || probeTimeoutMillis <= 0 || drainMillis < 0 || port < 0 || port > 65535) {
            throw new IllegalArgumentException("磁盘保护参数无效，恢复条件必须比触发条件保留更多空间");
        }
    }

    public Path marker() { return workPath.resolve(".biliup-disk-protection"); }

    public static Path localDatabasePath(String url) {
        if (url == null || !url.startsWith("jdbc:h2:")) return null;
        String location = url.substring(8).split(";", 2)[0];
        while (location.startsWith("retry:")) location = location.substring(6);
        if (location.startsWith("mem:") || location.startsWith("tcp:") || location.startsWith("ssl:")) return null;
        if (location.startsWith("file:")) location = location.substring(5);
        return location.isBlank() ? null : path(location);
    }

    private static Path path(String value) {
        String normalized = value.replace("\\\\", "\\").replace('\\', '/');
        if (normalized.startsWith("~/")) normalized = System.getProperty("user.home") + normalized.substring(1);
        return Path.of(normalized).toAbsolutePath().normalize();
    }
}
