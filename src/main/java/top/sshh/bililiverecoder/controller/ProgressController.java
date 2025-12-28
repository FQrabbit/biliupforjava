package top.sshh.bililiverecoder.controller;

import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sshh.bililiverecoder.util.UploadProgressTracker;

import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/progress")
public class ProgressController {

    @Autowired
    private UploadProgressTracker tracker;

    @GetMapping("/part/{partId}")
    public PartProgressResponse part(@PathVariable("partId") Long partId) {
        PartProgressResponse resp = new PartProgressResponse();
        if (partId == null) {
            resp.setFound(false);
            return resp;
        }
        UploadProgressTracker.Progress p = tracker.getByPartId(partId);
        if (p == null) {
            resp.setFound(false);
            return resp;
        }
        resp.setFound(true);
        resp.setProgress(p);
        return resp;
    }

    @GetMapping("/history/{historyId}")
    public HistoryProgressResponse history(@PathVariable("historyId") Long historyId) {
        HistoryProgressResponse resp = new HistoryProgressResponse();
        if (historyId == null) {
            resp.setHistoryId(null);
            resp.setActiveCount(0);
            resp.setOverallPercent(0);
            resp.setItems(List.of());
            return resp;
        }

        List<UploadProgressTracker.Progress> list = tracker.listByHistoryId(historyId);
        list.sort(Comparator.comparingLong(UploadProgressTracker.Progress::getUpdateAtMs).reversed());

        int active = 0;
        int sum = 0;
        int n = 0;
        for (UploadProgressTracker.Progress p : list) {
            if (p == null) continue;
            if (p.isActive()) {
                active++;
                sum += Math.max(0, Math.min(100, p.getPercent()));
                n++;
            }
        }

        resp.setHistoryId(historyId);
        resp.setItems(list);
        resp.setActiveCount(active);
        resp.setOverallPercent(n <= 0 ? 0 : (int) Math.round(sum * 1.0 / n));
        return resp;
    }

    @Data
    public static class PartProgressResponse {
        private boolean found;
        private UploadProgressTracker.Progress progress;
    }

    @Data
    public static class HistoryProgressResponse {
        private Long historyId;
        private int activeCount;
        private int overallPercent;
        private List<UploadProgressTracker.Progress> items;
    }
}
