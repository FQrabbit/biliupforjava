package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class PartFileStorageAdapter {

    public boolean isRegularFile(Path path) {
        return path != null && Files.isRegularFile(path);
    }

    public long size(Path path) throws IOException {
        return Files.size(path);
    }

    public void createDirectories(Path path) throws IOException {
        if (path != null) Files.createDirectories(path);
    }

    public void move(Path source, Path target) throws IOException {
        Files.move(source, target);
    }

    public void copy(Path source, Path target) throws IOException {
        Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }

    public void delete(Path path) throws IOException {
        Files.deleteIfExists(path);
    }
}
