package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 无法确定关联的数据单独保留，不参与任何任务 */
@Data
@Entity
@Table(indexes = @Index(name = "idx_backup_quarantine_batch", columnList = "batchId"))
public class BackupQuarantineRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String batchId;
    private String section;
    private String sourceKey;
    private String reason;
    @Lob
    private String payload;
    private LocalDateTime createdAt;
}
