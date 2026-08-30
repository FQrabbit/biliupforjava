package top.sshh.bililiverecoder.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartPreviewServiceTest {

    @Test
    void skipsInvalidPathEntryAndFindsFfmpegInLaterEntry(@TempDir Path directory) throws Exception {
        PartPreviewService service = new PartPreviewService();
        Files.createFile(directory.resolve("ffmpeg.exe"));

        String invalidEntry = "bad\u0000path";
        String path = invalidEntry + java.io.File.pathSeparator + directory;

        assertEquals(directory.resolve("ffmpeg.exe").toAbsolutePath().normalize().toString(),
                service.findCommandOnPath(path, "ffmpeg.exe"));
    }

    @Test
    void returnsNullWhenAllPathEntriesAreInvalid() {
        PartPreviewService service = new PartPreviewService();
        assertNull(service.findCommandOnPath("bad\u0000path" + java.io.File.pathSeparator + "other\u0000path", "ffmpeg.exe"));
    }

    @Test
    void ignoresBlankAndMissingPathEntries() {
        PartPreviewService service = new PartPreviewService();
        assertNull(service.findCommandOnPath(java.io.File.pathSeparator + "missing-dir" + java.io.File.pathSeparator, "ffmpeg.exe"));
    }

    @Test
    void logsEachInvalidEntryOncePerPathSnapshot() {
        PartPreviewService service = new PartPreviewService();
        String path = "secret-bad\u0000path";
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            service.findCommandOnPath(path, "ffmpeg.exe");
            service.findCommandOnPath(path, "ffmpeg.exe");

            List<ILoggingEvent> events = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("PartPreview.FFmpeg.InvalidPathEntry"))
                    .toList();
            assertEquals(1, events.size());
            String message = events.get(0).getFormattedMessage();
            assertTrue(message.contains("index=0"));
            assertTrue(message.contains("entry=" + path));
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void logsAgainWhenPathSnapshotChanges() {
        PartPreviewService service = new PartPreviewService();
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            service.findCommandOnPath("first\u0000path", "ffmpeg.exe");
            service.findCommandOnPath("second\u0000path", "ffmpeg.exe");

            long count = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("PartPreview.FFmpeg.InvalidPathEntry"))
                    .count();
            assertEquals(2, count);
        } finally {
            detachAppender(appender);
        }
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(PartPreviewService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(PartPreviewService.class);
        logger.detachAppender(appender);
    }
}
