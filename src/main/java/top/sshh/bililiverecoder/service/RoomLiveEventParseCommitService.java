package top.sshh.bililiverecoder.service;

import com.alibaba.fastjson.JSON;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.sshh.bililiverecoder.entity.RoomLiveDanmuUserStats;
import top.sshh.bililiverecoder.entity.RoomLiveEvent;
import top.sshh.bililiverecoder.entity.RoomLiveEventParseState;
import top.sshh.bililiverecoder.repo.RoomLiveDanmuUserStatsRepository;
import top.sshh.bililiverecoder.repo.RoomLiveEventParseStateRepository;
import top.sshh.bililiverecoder.repo.RoomLiveEventRepository;

import java.util.List;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class RoomLiveEventParseCommitService {
    private final RoomLiveEventRepository eventRepository;
    private final RoomLiveDanmuUserStatsRepository danmuStatsRepository;
    private final RoomLiveEventParseStateRepository stateRepository;
    @PersistenceContext
    private EntityManager entityManager;

    public RoomLiveEventParseCommitService(RoomLiveEventRepository eventRepository,
                                           RoomLiveDanmuUserStatsRepository danmuStatsRepository,
                                           RoomLiveEventParseStateRepository stateRepository) {
        this.eventRepository = eventRepository;
        this.danmuStatsRepository = danmuStatsRepository;
        this.stateRepository = stateRepository;
    }

    @Transactional
    public void replacePartData(Long partId, List<RoomLiveEvent> events,
                                List<RoomLiveDanmuUserStats> danmuStats,
                                RoomLiveEventParseState state) {
        eventRepository.deleteByPartId(partId);
        danmuStatsRepository.deleteByPartId(partId);
        if (events != null && !events.isEmpty()) eventRepository.saveAll(events);
        if (danmuStats != null && !danmuStats.isEmpty()) danmuStatsRepository.saveAll(danmuStats);
        stateRepository.save(state);
    }

    @Transactional
    public void replacePartDataFromSpool(Long partId, Path eventSpool,
                                         List<RoomLiveDanmuUserStats> danmuStats,
                                         RoomLiveEventParseState state, int batchSize) {
        int safeBatchSize = Math.max(50, Math.min(2000, batchSize));
        eventRepository.deleteByPartId(partId);
        danmuStatsRepository.deleteByPartId(partId);
        entityManager.flush();
        entityManager.clear();

        int pending = 0;
        try (BufferedReader reader = Files.newBufferedReader(eventSpool, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                entityManager.persist(JSON.parseObject(line, RoomLiveEvent.class));
                if (++pending >= safeBatchSize) {
                    entityManager.flush();
                    entityManager.clear();
                    pending = 0;
                }
            }
            for (RoomLiveDanmuUserStats stats : danmuStats) {
                entityManager.persist(stats);
                if (++pending >= safeBatchSize) {
                    entityManager.flush();
                    entityManager.clear();
                    pending = 0;
                }
            }
            stateRepository.save(state);
            entityManager.flush();
        } catch (IOException error) {
            throw new IllegalStateException("读取 XML 解析暂存文件失败", error);
        }
    }
}
