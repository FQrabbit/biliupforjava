package top.sshh.bililiverecoder.protection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class DiskProtectionMarker {
    private final Path path;
    public DiskProtectionMarker(Path path) { this.path = path; }

    public boolean exists() throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("保护状态文件不能是符号链接");
        if (Files.notExists(path)) return false;
        if (!Files.isRegularFile(path) || Files.size(path) > 4096) throw new IOException("保护状态文件无法读取");
        String value = Files.readString(path, StandardCharsets.UTF_8);
        if (!value.startsWith("BILIUP_DISK_PROTECTION_V1\n")) throw new IOException("保护状态文件格式异常");
        return true;
    }

    public void write() throws IOException {
        Files.createDirectories(path.getParent());
        if (Files.isSymbolicLink(path)) throw new IOException("保护状态文件不能是符号链接");
        Path pending = path.resolveSibling(path.getFileName() + ".pending");
        byte[] bytes = ("BILIUP_DISK_PROTECTION_V1\n" + java.time.Instant.now() + "\n").getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(pending, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try { Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING); }
    }

    public void clear() throws IOException { Files.deleteIfExists(path); }
}
