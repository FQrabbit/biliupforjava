package top.sshh.bililiverecoder.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class ContainerUtils {

    private static volatile Boolean cached;

    private ContainerUtils() {
    }

    /**
     * 检测是否运行在容器环境（Docker / containerd / k8s / Podman）
     * 结果会缓存，避免重复 I/O
     */
    public static boolean isRunningInContainer() {
        if (cached != null) {
            return cached;
        }
        cached = detectContainer();
        return cached;
    }

    private static boolean detectContainer() {
        if (new File("/.dockerenv").exists()) {
            return true;
        }
        File cgroup = new File("/proc/1/cgroup");
        if (!cgroup.exists()) {
            return false;
        }
        try {
            String content = Files.readString(cgroup.toPath(), StandardCharsets.UTF_8).toLowerCase();
            return content.contains("docker")
                    || content.contains("containerd")
                    || content.contains("kubepods")
                    || content.contains("podman");
        } catch (IOException ignored) {
            return false;
        }
    }
}
