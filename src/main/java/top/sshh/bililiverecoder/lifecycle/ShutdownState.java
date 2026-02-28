package top.sshh.bililiverecoder.lifecycle;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ShutdownState {

    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    public boolean markShuttingDown() {
        return shuttingDown.compareAndSet(false, true);
    }
}

