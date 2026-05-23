package top.sshh.bililiverecoder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"sessionId", "partNumber"}),
       indexes = {
           @Index(name = "idx_multipart_part_session", columnList = "sessionId"),
           @Index(name = "idx_multipart_part_session_status", columnList = "sessionId,status")
       })
public class MultipartUploadPart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long sessionId;

    private Integer partNumber;

    @Column(length = 256)
    private String etag;

    private Long startByte;

    private Long endByte;

    private Long sizeBytes;

    @Column(length = 32)
    private String status;

    private LocalDateTime updatedAt;
}
