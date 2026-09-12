package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Service;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.entity.RoomLiveEventXmlIssue;
import top.sshh.bililiverecoder.repo.RecordHistoryPartRepository;
import top.sshh.bililiverecoder.util.XmlRepairTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

@Service
public class RoomLiveEventXmlRepairService {
    private final RecordHistoryPartRepository partRepository;
    private final PartFileLocationService locationService;
    private final RoomLiveEventXmlIssueService issueService;

    public RoomLiveEventXmlRepairService(RecordHistoryPartRepository partRepository,
                                         PartFileLocationService locationService,
                                         RoomLiveEventXmlIssueService issueService) {
        this.partRepository = partRepository;
        this.locationService = locationService;
        this.issueService = issueService;
    }

    // 调用时持有统计信息写锁，该锁与解析器共用
    public Path repair(RecordHistoryPart part) throws IOException {
        Object backup = repairWithReport(part).get("backupPath");
        return backup == null ? null : Path.of(backup.toString());
    }

    public java.util.Map<String, Object> repairWithReport(RecordHistoryPart part) throws IOException {
        requireInactive(part);
        var issue = issueService.find(part.getId()).orElseThrow(() ->
                new IllegalArgumentException("该 XML 问题已不存在，请刷新列表"));
        if (issue.getIssueType() != RoomLiveEventXmlIssue.IssueType.INVALID_XML) {
            throw new IllegalArgumentException("仅解析失败的 XML 可以自动修复");
        }
        var resolution = locationService.resolveCompanionState(part.getId(), ".xml");
        if (!resolution.available()) {
            throw new IOException("XML 文件不可用：" + resolution.message());
        }
        Path source = resolution.path().toAbsolutePath().normalize();
        BasicFileAttributes before = Files.readAttributes(source, BasicFileAttributes.class);
        if (!before.isRegularFile()) throw new IOException("XML 路径不是普通文件");
        Path repaired = null;
        Path staged = null;
        Path backup = null;
        try {
            XmlRepairTool.StreamRepairResult result;
            try (var input = Files.newInputStream(source)) {
                result = XmlRepairTool.streamRepair(input);
            }
            repaired = result.tempFile();
            if (!result.after().valid()) {
                throw new IOException("无法自动修复，原文件未替换：" + result.after().message());
            }
            java.util.Map<String, Object> diagnostic = new java.util.LinkedHashMap<>(result.report());
            if (!result.changed()) return diagnostic;
            staged = Files.createTempFile(source.getParent(), ".xml-repair-", ".tmp");
            Files.copy(repaired, staged, StandardCopyOption.REPLACE_EXISTING);
            requireInactive(partRepository.findById(part.getId()).orElseThrow(() ->
                    new IOException("分P记录已不存在")));
            var current = locationService.resolveCompanionState(part.getId(), ".xml");
            if (!current.available() || !source.equals(current.path().toAbsolutePath().normalize())) {
                throw new IOException("XML 文件位置已变化，请重新检查后再修复");
            }
            verifyUnchanged(source, before);
            backup = Files.createTempFile(source.getParent(), source.getFileName() + ".", ".bak");
            Files.copy(source, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            verifyUnchanged(source, before);
            // 不允许非原子回退：替换失败时，源必须保持原样
            Files.move(staged, source, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            diagnostic.put("backupPath", backup.toString());
            return diagnostic;
        } finally {
            if (repaired != null) Files.deleteIfExists(repaired);
            if (staged != null) Files.deleteIfExists(staged);
        }
    }

    private void requireInactive(RecordHistoryPart part) {
        if (part.isRecording() || part.getEndTime() == null) {
            throw new IllegalArgumentException("该分P仍在录制或尚未结束，不能替换 XML 文件");
        }
    }

    private void verifyUnchanged(Path source, BasicFileAttributes before) throws IOException {
        var current = Files.readAttributes(source, BasicFileAttributes.class);
        if (before.size() != current.size() || !before.lastModifiedTime().equals(current.lastModifiedTime())
                || !Objects.equals(before.fileKey(), current.fileKey())) {
            throw new IOException("XML 文件在修复期间发生变化，原文件未替换，请稍后重试");
        }
    }
}
