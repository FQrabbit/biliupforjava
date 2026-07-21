package top.sshh.bililiverecoder.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "room_live_event_xml_issue", indexes = {
        @Index(name = "idx_room_live_xml_issue_type_ignored", columnList = "issueType,ignoredAt"),
        @Index(name = "idx_room_live_xml_issue_history", columnList = "historyId"),
        @Index(name = "idx_room_live_xml_issue_root", columnList = "storageRootId")
})
public class RoomLiveEventXmlIssue {

    public enum IssueType {
        MISSING_UNEXPECTED,
        INVALID_XML,
        READ_FAILED,
        ROOT_OFFLINE,
        PATH_UNRESOLVED,
        INTERNAL_ERROR
    }

    @Id
    private Long partId;

    private Long historyId;
    private String roomId;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private IssueType issueType;

    private Long storageRootId;

    @Column(length = 2000)
    private String xmlPath;

    @Column(length = 1000)
    private String errorMessage;

    private LocalDateTime firstDetectedAt;
    private LocalDateTime lastCheckedAt;
    private LocalDateTime ignoredAt;
}
