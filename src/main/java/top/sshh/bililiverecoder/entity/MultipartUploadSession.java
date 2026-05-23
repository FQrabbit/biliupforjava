package top.sshh.bililiverecoder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(indexes = {
        @Index(name = "idx_multipart_session_part_status", columnList = "partId,status"),
        @Index(name = "idx_multipart_session_history", columnList = "historyId")
})
public class MultipartUploadSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long partId;

    private Long historyId;

    @Column(length = 128)
    private String uploadId;

    @Column(length = 1024)
    private String uri;

    @Column(length = 2048)
    private String uploadToken;

    private Long bizId;

    @Column(length = 64)
    private String profile;

    private Long chunkSize;

    private Integer chunkTotal;

    private Long fileSize;

    @Column(length = 32)
    private String status;

    @Column(length = 512)
    private String lastError;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
