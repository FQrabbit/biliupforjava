package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;
import java.util.Arrays;
import org.springframework.test.util.ReflectionTestUtils;
import top.sshh.bililiverecoder.controller.LogController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogArchiveServiceTest {

    @Test
    void truncatedArchiveExplainsFailureInHistoryAndContext(@TempDir Path tempDir) throws Exception {
        String name = "spring.log.2026-08-07.0.gz";
        Path archive = tempDir.resolve(name);
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(archive))) {
            output.write("example log line\n".repeat(100).getBytes(StandardCharsets.UTF_8));
        }
        byte[] compressed = Files.readAllBytes(archive);
        Files.write(archive, Arrays.copyOf(compressed, compressed.length / 2));
        LogArchiveService service = new LogArchiveService();
        ReflectionTestUtils.setField(service, "logPath", tempDir.toString());
        ReflectionTestUtils.setField(service, "logName", "spring.log");
        LogController controller = new LogController(null, service, null);

        for (String message : new String[] {controller.getHistoryLogs(20).get(0),
                controller.getContextLogs("example", 5).get(0)}) {
            assertTrue(message.startsWith("读取日志文件失败: "));
            assertTrue(message.contains("日志压缩包「" + name + "」"));
            assertTrue(message.contains("可能是磁盘空间不足"));
            assertTrue(message.contains("Unexpected end of ZLIB input stream"));
        }
    }

    @Test
    void countsPlainLogBytes(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("spring.log");
        byte[] content = "one\ntwo\n".getBytes(StandardCharsets.UTF_8);
        Files.write(path, content);
        LogArchiveService service = new LogArchiveService();
        AtomicLong counted = new AtomicLong();

        try (BufferedReader reader = service.reader(
                new LogArchiveService.LogFile(path, null, Integer.MAX_VALUE, true, content.length, content.length),
                counted::addAndGet)) {
            while (reader.readLine() != null) { }
        }

        assertEquals(content.length, counted.get());
    }

    @Test
    void countsCompressedSourceBytesAndHonorsSnapshot(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("spring.log.2026-08-07.0.gz");
        byte[] content = "a long enough log line for gzip counting\n".repeat(10).getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(path))) {
            output.write(content);
        }
        long compressedSize = Files.size(path);
        LogArchiveService service = new LogArchiveService();
        AtomicLong counted = new AtomicLong();
        long snapshot = Math.max(1, compressedSize / 2);

        try (BufferedReader reader = service.reader(
                new LogArchiveService.LogFile(path, null, 0, false, compressedSize, snapshot),
                counted::addAndGet)) {
            while (reader.readLine() != null) { }
        } catch (Exception ignored) {
            // 截断 GZIP 可能在读到快照末尾时报告 EOF，但计数仍应遵守快照长度
        }

        assertEquals(snapshot, counted.get());
    }
}
