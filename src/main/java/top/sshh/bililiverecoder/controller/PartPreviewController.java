package top.sshh.bililiverecoder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import top.sshh.bililiverecoder.service.PartPreviewService;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/part/preview")
public class PartPreviewController {

    private static final int BUFFER_SIZE = 1024 * 1024;

    @Autowired
    private PartPreviewService previewService;

    @GetMapping("/{partId}/meta")
    public Map<String, Object> meta(@PathVariable Long partId) {
        return previewService.meta(partId);
    }

    @PostMapping("/{partId}/prepare")
    public Map<String, Object> prepare(@PathVariable Long partId) {
        return previewService.prepare(partId);
    }

    @GetMapping("/{partId}/task")
    public Map<String, Object> task(@PathVariable Long partId) {
        return previewService.task(partId);
    }

    @PostMapping("/{partId}/cancel")
    public Map<String, Object> cancel(@PathVariable Long partId) {
        return previewService.cancel(partId);
    }

    @GetMapping("/{partId}/source")
    public ResponseEntity<StreamingResponseBody> source(@PathVariable Long partId,
                                                        @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        return stream(previewService.getSource(partId), rangeHeader);
    }

    @GetMapping("/{partId}/cache")
    public ResponseEntity<StreamingResponseBody> cache(@PathVariable Long partId,
                                                       @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        return stream(previewService.getCache(partId), rangeHeader);
    }

    private ResponseEntity<StreamingResponseBody> stream(PartPreviewService.PreviewFile previewFile, String rangeHeader) {
        if (previewFile == null || !previewFile.available() || previewFile.streamFile() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        try {
            Path file = previewFile.streamFile();
            long length = Files.size(file);
            Range range = parseRange(rangeHeader, length);
            StreamingResponseBody body = outputStream -> {
                try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
                    raf.seek(range.start);
                    byte[] buffer = new byte[BUFFER_SIZE];
                    long remaining = range.length();
                    while (remaining > 0) {
                        int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (read < 0) {
                            break;
                        }
                        try {
                            outputStream.write(buffer, 0, read);
                        } catch (IOException e) {
                            if (isClientAbort(e)) {
                                return;
                            }
                            throw e;
                        }
                        remaining -= read;
                    }
                    try {
                        outputStream.flush();
                    } catch (IOException e) {
                        if (!isClientAbort(e)) {
                            throw e;
                        }
                    }
                }
            };

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
            headers.setContentType(MediaType.parseMediaType(previewFile.contentType() == null ? "application/octet-stream" : previewFile.contentType()));
            headers.setContentLength(range.length());
            if (range.partial) {
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + range.start + "-" + range.end + "/" + length);
                return new ResponseEntity<>(body, headers, HttpStatus.PARTIAL_CONTENT);
            }
            return new ResponseEntity<>(body, headers, HttpStatus.OK);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    private boolean isClientAbort(Throwable e) {
        Throwable current = e;
        while (current != null) {
            String name = current.getClass().getName();
            String message = current.getMessage();
            if (name.contains("ClientAbortException")
                    || name.contains("ClosedChannelException")
                    || containsIgnoreCase(message, "broken pipe")
                    || containsIgnoreCase(message, "connection reset")
                    || containsIgnoreCase(message, "远程主机强迫关闭")
                    || containsIgnoreCase(message, "你的主机中的软件中止了一个已建立的连接")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && needle != null && value.toLowerCase().contains(needle.toLowerCase());
    }

    private Range parseRange(String header, long length) {
        if (length <= 0 || header == null || !header.startsWith("bytes=")) {
            return new Range(0, Math.max(0, length - 1), false);
        }
        try {
            String spec = header.substring("bytes=".length()).split(",", 2)[0].trim();
            int dash = spec.indexOf('-');
            if (dash < 0) {
                return new Range(0, length - 1, false);
            }
            String startPart = spec.substring(0, dash).trim();
            String endPart = spec.substring(dash + 1).trim();
            long start;
            long end;
            if (startPart.isEmpty()) {
                long suffix = Long.parseLong(endPart);
                start = Math.max(0, length - suffix);
                end = length - 1;
            } else {
                start = Long.parseLong(startPart);
                end = endPart.isEmpty() ? length - 1 : Long.parseLong(endPart);
            }
            start = Math.max(0, Math.min(start, length - 1));
            end = Math.max(start, Math.min(end, length - 1));
            return new Range(start, end, true);
        } catch (Exception e) {
            return new Range(0, length - 1, false);
        }
    }

    private record Range(long start, long end, boolean partial) {
        private long length() {
            return Math.max(0, end - start + 1);
        }
    }
}
