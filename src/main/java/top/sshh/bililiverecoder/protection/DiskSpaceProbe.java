package top.sshh.bililiverecoder.protection;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class DiskSpaceProbe {
    public record Volume(String path, long totalBytes, long freeBytes, double usedPercent) {
        public boolean low(DiskSpaceSettings settings) {
            return usedPercent >= settings.stopPercent() || freeBytes <= settings.stopBytes();
        }
        public boolean recovered(DiskSpaceSettings settings) {
            return usedPercent <= settings.resumePercent() && freeBytes >= settings.resumeBytes();
        }
        public Map<String, Object> toMap() {
            return Map.of("path", path, "totalBytes", totalBytes, "freeBytes", freeBytes, "usedPercent", usedPercent);
        }
    }

    private record Anchor(Path path, Path realPath, FileStore store) {}
    private final Map<Path, Anchor> anchors = new HashMap<>();
    private final Path historyFile;
    private final Properties identities = new Properties();
    private boolean loaded;
    private boolean dirty;

    public DiskSpaceProbe() { this(null); }
    public DiskSpaceProbe(Path historyFile) { this.historyFile = historyFile; }

    public List<Volume> collect(List<Path> paths) throws IOException {
        loadIdentities();
        List<Volume> result = new ArrayList<>();
        Set<FileStore> seen = new HashSet<>();
        for (Path requested : paths) {
            String expected = identities.getProperty(requested.toString());
            if (expected != null && !Files.isDirectory(requested)) throw new IOException("已登记的存储目录丢失：" + requested);
            Anchor anchor = anchors.get(requested);
            if (anchor == null) {
                Path existing = requested;
                // 首次部署允许目录尚未创建，之后固定探测位置，不能在挂载丢失后退回父卷
                while (existing != null && !Files.exists(existing)) existing = existing.getParent();
                if (existing == null || !Files.isDirectory(existing)) throw new IOException("无法定位存储目录：" + requested);
                anchor = new Anchor(existing, existing.toRealPath(), Files.getFileStore(existing));
                anchors.put(requested, anchor);
            }
            if (!Files.isDirectory(anchor.path()) || !Files.isReadable(anchor.path())
                    || !anchor.realPath().equals(anchor.path().toRealPath())
                    || !anchor.store().equals(Files.getFileStore(anchor.path()))) {
                throw new IOException("存储目录不可用或挂载已变化：" + requested);
            }
            // 目录创建成功后固定到真实目录，以便发现后续目录消失
            if (!anchor.path().equals(requested) && Files.isDirectory(requested)) {
                Anchor actual = new Anchor(requested, requested.toRealPath(), Files.getFileStore(requested));
                if (!anchor.store().equals(actual.store())) throw new IOException("新目录所在磁盘与启动检查不一致：" + requested);
                anchors.put(requested, actual);
                anchor = actual;
            }
            if (anchor.path().equals(requested)) {
                String actual = anchor.realPath() + "|" + anchor.store().name() + "|" + anchor.store().type();
                if (expected != null && !expected.equals(actual)) throw new IOException("存储目录与已登记磁盘不一致：" + requested);
                if (expected == null) { identities.setProperty(requested.toString(), actual); dirty = true; }
            }
            if (!seen.add(anchor.store())) continue;
            long total = anchor.store().getTotalSpace();
            long free = anchor.store().getUsableSpace();
            if (total <= 0 || free < 0 || free > total) throw new IOException("磁盘容量返回值无效：" + requested);
            result.add(new Volume(requested.toString(), total, free, (total - free) * 100d / total));
        }
        saveIdentities();
        return List.copyOf(result);
    }

    private void loadIdentities() throws IOException {
        if (loaded || historyFile == null) return;
        if (Files.exists(historyFile)) {
            if (Files.isSymbolicLink(historyFile) || !Files.isRegularFile(historyFile) || Files.size(historyFile) > 16384) {
                throw new IOException("存储目录登记文件异常：" + historyFile);
            }
            try (var reader = Files.newBufferedReader(historyFile, java.nio.charset.StandardCharsets.UTF_8)) { identities.load(reader); }
            if (!"1".equals(identities.getProperty("version"))) throw new IOException("存储目录登记文件版本异常");
        }
        loaded = true;
    }

    private void saveIdentities() throws IOException {
        if (!dirty || historyFile == null) return;
        Files.createDirectories(historyFile.getParent());
        identities.setProperty("version", "1");
        Path pending = historyFile.resolveSibling(historyFile.getFileName() + ".pending");
        try (var writer = Files.newBufferedWriter(pending, java.nio.charset.StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            identities.store(writer, "biliupforjava storage identities");
        }
        try (var channel = java.nio.channels.FileChannel.open(pending, StandardOpenOption.WRITE)) { channel.force(true); }
        try { Files.move(pending, historyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(pending, historyFile, StandardCopyOption.REPLACE_EXISTING); }
        dirty = false;
    }
}
