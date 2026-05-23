package top.sshh.bililiverecoder.service;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.MultipartUploadPart;
import top.sshh.bililiverecoder.entity.MultipartUploadSession;
import top.sshh.bililiverecoder.entity.RecordHistoryPart;
import top.sshh.bililiverecoder.repo.MultipartUploadPartRepository;
import top.sshh.bililiverecoder.repo.MultipartUploadSessionRepository;
import top.sshh.bililiverecoder.util.bili.upload.MultipartInitRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class MultipartUploadSessionService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_PAUSED = "PAUSED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String PART_DONE = "DONE";

    @Autowired
    private MultipartUploadSessionRepository sessionRepository;
    @Autowired
    private MultipartUploadPartRepository partRepository;

    public Optional<MultipartUploadSession> findReusableSession(Long partId, long fileSize) {
        Optional<MultipartUploadSession> opt = sessionRepository.findFirstByPartIdAndStatusInOrderByUpdatedAtDesc(
                partId,
                List.of(STATUS_ACTIVE, STATUS_PAUSED)
        );
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        MultipartUploadSession session = opt.get();
        if (session.getFileSize() == null || session.getFileSize() != fileSize || StringUtils.isAnyBlank(session.getUploadId(), session.getUri(), session.getUploadToken())) {
            markExpired(session, "multipart session context changed");
            return Optional.empty();
        }
        return opt;
    }

    @Transactional
    public MultipartUploadSession createSession(RecordHistoryPart part,
                                                MultipartInitRequest.MultipartInitInfo initInfo,
                                                long chunkSize,
                                                int chunkTotal,
                                                long fileSize) {
        MultipartUploadSession session = new MultipartUploadSession();
        session.setPartId(part.getId());
        session.setHistoryId(part.getHistoryId());
        session.setUploadId(initInfo.getUploadId());
        session.setUri(initInfo.getUri());
        session.setUploadToken(initInfo.getUploadToken());
        session.setBizId(initInfo.getBizId());
        session.setProfile(initInfo.getProfile());
        session.setChunkSize(chunkSize);
        session.setChunkTotal(chunkTotal);
        session.setFileSize(fileSize);
        session.setStatus(STATUS_ACTIVE);
        LocalDateTime now = LocalDateTime.now();
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        return sessionRepository.save(session);
    }

    @Transactional
    public MultipartUploadSession activate(MultipartUploadSession session) {
        session.setStatus(STATUS_ACTIVE);
        session.setUpdatedAt(LocalDateTime.now());
        session.setLastError(null);
        return sessionRepository.save(session);
    }

    @Transactional
    public void markPaused(MultipartUploadSession session, String reason) {
        if (session == null) {
            return;
        }
        session.setStatus(STATUS_PAUSED);
        session.setLastError(reason);
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);
    }

    @Transactional
    public void markExpired(MultipartUploadSession session, String reason) {
        if (session == null) {
            return;
        }
        if (session.getId() != null) {
            partRepository.deleteBySessionId(session.getId());
        }
        session.setStatus(STATUS_EXPIRED);
        session.setLastError(reason);
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);
    }

    @Transactional
    public void markCompletedAndClear(MultipartUploadSession session) {
        if (session == null || session.getId() == null) {
            return;
        }
        partRepository.deleteBySessionId(session.getId());
        session.setStatus(STATUS_COMPLETED);
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);
    }

    @Transactional
    public void saveCompletedPart(MultipartUploadSession session, int partNumber, String etag, long start, long end) {
        MultipartUploadPart part = partRepository.findBySessionIdAndPartNumber(session.getId(), partNumber)
                .orElseGet(MultipartUploadPart::new);
        part.setSessionId(session.getId());
        part.setPartNumber(partNumber);
        part.setEtag(etag);
        part.setStartByte(start);
        part.setEndByte(end);
        part.setSizeBytes(Math.max(0L, end - start));
        part.setStatus(PART_DONE);
        part.setUpdatedAt(LocalDateTime.now());
        partRepository.save(part);
        session.setStatus(STATUS_ACTIVE);
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);
    }

    public Optional<MultipartUploadPart> findCompletedPart(MultipartUploadSession session, int partNumber) {
        if (session == null || session.getId() == null) {
            return Optional.empty();
        }
        return partRepository.findBySessionIdAndPartNumber(session.getId(), partNumber)
                .filter(p -> PART_DONE.equals(p.getStatus()) && StringUtils.isNotBlank(p.getEtag()));
    }

    public List<MultipartUploadPart> listCompletedParts(MultipartUploadSession session) {
        if (session == null || session.getId() == null) {
            return List.of();
        }
        return partRepository.findBySessionIdAndStatus(session.getId(), PART_DONE);
    }

    public int countCompletedParts(MultipartUploadSession session) {
        if (session == null || session.getId() == null) {
            return 0;
        }
        return (int) partRepository.countBySessionIdAndStatus(session.getId(), PART_DONE);
    }
}
