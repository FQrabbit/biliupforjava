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

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogArchiveServiceTest {

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
