package top.sshh.bililiverecoder.protection;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

public class DiskProtectionLogFilter extends Filter<ILoggingEvent> {
    @Override public FilterReply decide(ILoggingEvent event) {
        return DiskSpaceGuard.blocksBusiness() ? FilterReply.DENY : FilterReply.NEUTRAL;
    }
}
