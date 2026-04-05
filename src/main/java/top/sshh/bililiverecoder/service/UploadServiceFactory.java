package top.sshh.bililiverecoder.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;
import top.sshh.bililiverecoder.service.impl.AppRecordPartBilibiliUploadService;
import top.sshh.bililiverecoder.service.impl.KodoRecordPartBilibiliUploadService;
import top.sshh.bililiverecoder.service.impl.UposRecordPartBilibiliUploadService;
import top.sshh.bililiverecoder.util.UploadEnums;

@Slf4j
@Component
public class UploadServiceFactory {


    @Lazy
    @Autowired
    @Qualifier("appRecordPartBilibiliUploadService")
    private RecordPartUploadService appRecordPartBilibiliUploadService;

    @Lazy
    @Autowired
    @Qualifier("uposRecordPartBilibiliUploadService")
    private RecordPartUploadService uposRecordPartBilibiliUploadService;

    @Lazy
    @Autowired
    @Qualifier("kodoRecordPartBilibiliUploadService")
    private RecordPartUploadService kodoRecordPartBilibiliUploadService;


    public RecordPartUploadService getUploadService(String line) {
        UploadEnums uploadEnums = UploadEnums.find(line);
        return switch (uploadEnums.getOs()) {
            case AppRecordPartBilibiliUploadService.OS -> appRecordPartBilibiliUploadService;
            case UposRecordPartBilibiliUploadService.OS -> uposRecordPartBilibiliUploadService;
            case KodoRecordPartBilibiliUploadService.OS -> kodoRecordPartBilibiliUploadService;
            default -> uposRecordPartBilibiliUploadService;
        };
    }
}
