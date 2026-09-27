package top.sshh.bililiverecoder.entity;

import java.util.EnumSet;
import java.util.Set;

public enum PublishTaskState {
    READY,
    PREPARING,
    WAITING_UPLOAD,
    WAITING_ACCOUNT,
    WAITING_CAPTCHA,
    SUBMITTING,
    VERIFYING,
    RETRY_WAIT,
    NEEDS_ACTION,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    private static final Set<PublishTaskState> ACTIVE = EnumSet.of(
            READY, PREPARING, WAITING_UPLOAD, WAITING_ACCOUNT, WAITING_CAPTCHA,
            SUBMITTING, VERIFYING, RETRY_WAIT, NEEDS_ACTION);

    public boolean isActive() { return ACTIVE.contains(this); }

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
