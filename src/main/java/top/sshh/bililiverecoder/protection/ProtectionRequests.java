package top.sshh.bililiverecoder.protection;

import java.util.HashMap;
import java.util.Map;

public final class ProtectionRequests {
    private static final Map<Thread, Integer> active = new HashMap<>();
    private ProtectionRequests() {}
    public static synchronized boolean enter() {
        if (DiskSpaceGuard.blocksBusiness()) return false;
        active.merge(Thread.currentThread(), 1, Integer::sum);
        return true;
    }
    public static synchronized void leave() {
        active.computeIfPresent(Thread.currentThread(), (thread, count) -> count == 1 ? null : count - 1);
    }
    public static synchronized boolean isIdle() { return active.isEmpty(); }
}
