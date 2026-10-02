package top.sshh.bililiverecoder.config.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 大文件用固定大小的缓冲区传输，不把整库装进内存 */
public final class DatabaseFileTransfer {
    private DatabaseFileTransfer() { }

    public static Path localWorkspace() throws IOException {
        Path root = Path.of(System.getProperty("java.io.tmpdir"), "biliupforjava-maintenance");
        Files.createDirectories(root);
        return Files.createTempDirectory(root, "database-");
    }

    public static void copy(Path source, Path target, BiConsumer<Long, Long> progress) throws IOException {
        long total = Files.size(source);
        long written = 0;
        byte[] buffer = new byte[1024 * 1024];
        progress.accept(0L, total);
        try (InputStream input = Files.newInputStream(source);
             FileChannel output = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("数据库文件传输已中断");
                ByteBuffer bytes = ByteBuffer.wrap(buffer, 0, size);
                while (bytes.hasRemaining()) output.write(bytes);
                written += size;
                progress.accept(written, total);
            }
            output.force(true);
        }
        if (written != total || Files.size(target) != total) throw new IOException("数据库文件传输不完整");
    }

    public static void publishBackup(Path localBackup, Path target, BiConsumer<Long, Long> progress) throws IOException {
        Files.createDirectories(target.getParent());
        Path pending = target.resolveSibling(target.getFileName() + ".pending-" + UUID.randomUUID());
        try {
            copy(localBackup, pending, progress);
            Files.move(pending, target);
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    public static void restoreSnapshot(Path backup, Path databaseFile) throws IOException {
        boolean found = false;
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(backup))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".mv.db")) {
                    if (found) throw new IOException("备份中包含多个数据库，无法确定压缩目标");
                    // 只写到指定文件，不使用压缩包里的路径
                    Files.copy(input, databaseFile);
                    found = true;
                }
                input.closeEntry();
            }
        }
        if (!found) throw new IOException("备份中没有找到 H2 数据库文件");
    }

    public static void cleanWorkspace(Path directory) throws IOException {
        if (directory == null) return;
        // 临时目录只存本次维护生成的文件，不递归删除其他目录
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) Files.delete(child);
        }
        Files.delete(directory);
    }
}
