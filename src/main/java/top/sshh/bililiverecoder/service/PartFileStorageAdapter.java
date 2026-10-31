package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class PartFileStorageAdapter {

    public boolean isRegularFile(Path path) {
        return path != null && Files.isRegularFile(path);
    }

    public long size(Path path) throws IOException {
        return Files.size(path);
    }

    public void createDirectories(Path path) throws IOException {
        requireRunning();
        if (path != null) Files.createDirectories(path);
    }

    public void move(Path source, Path target) throws IOException {
        requireRunning();
        if (Files.getFileStore(source).equals(Files.getFileStore(target.toAbsolutePath().getParent()))) {
            Files.move(source, target);
        } else {
            copy(source, target);
            if (Files.size(source) != Files.size(target)) throw new IOException("跨盘移动文件校验失败，原文件已保留");
            requireRunning();
            Files.delete(source);
        }
    }

    public void copy(Path source, Path target) throws IOException {
        requireRunning();
        try (var input = Files.newInputStream(source);
             var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) { requireRunning(); output.write(buffer, 0, count); }
        }
        requireRunning();
        Files.setLastModifiedTime(target, Files.getLastModifiedTime(source));
    }

    public void delete(Path path) throws IOException {
        requireRunning();
        Files.deleteIfExists(path);
    }

    protected void requireRunning() throws IOException {
        if (Thread.currentThread().isInterrupted() || top.sshh.bililiverecoder.protection.DiskSpaceGuard.blocksBusiness()) {
            throw new java.io.InterruptedIOException("磁盘保护或任务中断，文件操作已停止");
        }
    }
}
