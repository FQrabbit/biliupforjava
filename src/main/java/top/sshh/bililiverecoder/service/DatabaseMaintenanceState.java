package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DatabaseMaintenanceState {

    private final AtomicBoolean maintenanceActive = new AtomicBoolean(false);

    public boolean isMaintenanceActive() {
        return maintenanceActive.get();
    }

    public void setMaintenanceActive(boolean active) {
        maintenanceActive.set(active);
    }
}
