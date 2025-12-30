package top.sshh.bililiverecoder.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"eventId", "filePath"}))
public class RecordHistoryPart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String roomId;
    private Long historyId;
    /**
     * 视频oid
     */
    private Long cid;

    /**
     * 直播标题
     */
    private String liveTitle;

    private String title;

    private String areaName;

    private String filePath;

    private int page;

    private float duration;

    /**
     * 投稿服务器返回的文件名
     */
    private String fileName;

    private long fileSize;

    private String eventId;

    private String sessionId;


    private boolean recording;

    private boolean upload;

    @Column(name = "file_delete", columnDefinition = "bit default 0")
    private boolean fileDelete;

    @Column(name = "delete_retry_count", columnDefinition = "int default 0")
    private int deleteRetryCount;

    @Column(name = "delete_fail_reason", length = 512)
    private String deleteFailReason;

    private int uploadRetryCount;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private LocalDateTime updateTime;

    private boolean isPost;

    private String sourceType;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public Long getHistoryId() {
        return historyId;
    }

    public void setHistoryId(Long historyId) {
        this.historyId = historyId;
    }

    public Long getCid() {
        return cid;
    }

    public void setCid(Long cid) {
        this.cid = cid;
    }

    public String getLiveTitle() {
        return liveTitle;
    }

    public void setLiveTitle(String liveTitle) {
        this.liveTitle = liveTitle;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAreaName() {
        return areaName;
    }

    public void setAreaName(String areaName) {
        this.areaName = areaName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public float getDuration() {
        return duration;
    }

    public void setDuration(float duration) {
        this.duration = duration;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public boolean isRecording() {
        return recording;
    }

    public void setRecording(boolean recording) {
        this.recording = recording;
    }

    public boolean isUpload() {
        return upload;
    }

    public void setUpload(boolean upload) {
        this.upload = upload;
    }

    public boolean isFileDelete() {
        return fileDelete;
    }

    public void setFileDelete(boolean fileDelete) {
        this.fileDelete = fileDelete;
    }

    public int getDeleteRetryCount() {
        return deleteRetryCount;
    }

    public void setDeleteRetryCount(int deleteRetryCount) {
        this.deleteRetryCount = deleteRetryCount;
    }

    public String getDeleteFailReason() {
        return deleteFailReason;
    }

    public void setDeleteFailReason(String deleteFailReason) {
        this.deleteFailReason = deleteFailReason;
    }

    public int getUploadRetryCount() {
        return uploadRetryCount;
    }

    public void setUploadRetryCount(int uploadRetryCount) {
        this.uploadRetryCount = uploadRetryCount;
    }

    public LocalDateTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalDateTime startTime) {
        this.startTime = startTime;
    }

    public LocalDateTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalDateTime endTime) {
        this.endTime = endTime;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }

    public boolean isPost() {
        return isPost;
    }

    public void setPost(boolean post) {
        isPost = post;
    }

    public String getSourceType() {
        return sourceType;
    }

    public void setSourceType(String sourceType) {
        this.sourceType = sourceType;
    }
}
