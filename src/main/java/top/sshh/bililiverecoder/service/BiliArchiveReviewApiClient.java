package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Component;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse;
import top.sshh.bililiverecoder.entity.data.BiliVideoInfoResponse;
import top.sshh.bililiverecoder.entity.data.BiliVideoPartInfoResponse;
import top.sshh.bililiverecoder.util.BiliApi;

@Component
public class BiliArchiveReviewApiClient {

    public BiliVideoInfoResponse getVideoInfo(BiliBiliUser user, String bvid) {
        return BiliApi.getVideoInfo(user, bvid);
    }

    public BiliApi.ApiDebugResponse<BiliVideoPartInfoResponse> getVideoPartInfo(BiliBiliUser user, String bvid) {
        return BiliApi.getVideoPartInfoDebug(user, bvid);
    }

    public BiliApi.ApiDebugResponse<BiliVideoAuditDetailResponse> getAuditDetail(BiliBiliUser user, String bvid) {
        return BiliApi.getVideoAuditDetailDebug(user, bvid);
    }
}
