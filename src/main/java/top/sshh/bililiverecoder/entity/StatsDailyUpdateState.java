package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "stats_daily_update_state")
@IdClass(StatsDailyUpdateState.Key.class)
public class StatsDailyUpdateState {
    @Id private String roomId;
    @Id private LocalDate liveDate;
    @Column(columnDefinition = "bigint default 0", nullable = false)
    private long requestedRevision;
    @Column(columnDefinition = "bigint default 0", nullable = false)
    private long appliedRevision;

    @Data
    public static class Key implements Serializable {
        private String roomId;
        private LocalDate liveDate;
    }
}
