package top.sshh.bililiverecoder.util;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 日志键值对构建器
 *
 * 目标:
 * - 提高可读性
 * - 通过匹配字段名称，使前端掩码更可靠
 */
public final class LogKvs {

    private final StringBuilder sb = new StringBuilder();

    private String eventName;
    private boolean msgPresent;
    private boolean finalized;

    private static final Map<String, String> EVENT_ZH = new HashMap<>();
    static {
        EVENT_ZH.put("History.BadgeCount.CalcFailed", "计算历史记录角标数失败");
        EVENT_ZH.put("History.GiveUpCount.QueryFailed", "查询放弃分P数量失败");
        EVENT_ZH.put("History.GiveUpParts.QueryFailed", "查询放弃分P列表失败");
        EVENT_ZH.put("History.MergeInterval.InvalidConfig", "合并间隔时间配置无效");
        EVENT_ZH.put("History.Visibility.Switch.Success", "批量切换可见性成功");
        EVENT_ZH.put("History.Visibility.Switch.Error", "批量切换可见性失败");
        EVENT_ZH.put("History.Delete.Success", "删除录制历史成功");
        EVENT_ZH.put("History.DeleteMsg.Success", "删除弹幕成功");
        EVENT_ZH.put("History.ReloadMsg.Success", "重新加载弹幕成功");
        EVENT_ZH.put("History.MsgQueueCleanup.Done", "历史记录：待发送队列清理完成");
        EVENT_ZH.put("History.UpdatePartStatus.Success", "更新分P状态成功");
        EVENT_ZH.put("History.UpdatePublishStatus.Success", "更新投稿状态成功");
        EVENT_ZH.put("History.TouchPublish.Success", "触发发布成功");
        EVENT_ZH.put("History.HighEnergyCutPublish.Success", "触发高能剪辑成功");
        EVENT_ZH.put("History.Republish.Success", "触发转码修复成功");
        EVENT_ZH.put("History.ForceArchive.Success", "强制归档成功");

        // 上传
        EVENT_ZH.put("Upload.Part.AsyncStart", "分P上传任务开始(异步触发)");
        EVENT_ZH.put("Upload.Part.AlreadyUploading", "分P正在上传中，跳过重复触发");
        EVENT_ZH.put("Upload.Part.AlreadyQueued", "分P已在串行队列中，跳过重复入队");
        EVENT_ZH.put("Upload.Part.SkipPaused", "分P上传已暂停，跳过本次触发");
        EVENT_ZH.put("Upload.Part.SkipAlreadyUploaded", "分P已上传完成，跳过重复上传");
        EVENT_ZH.put("Upload.Part.MissingHistory", "找不到录制历史(history)，取消上传");
        EVENT_ZH.put("Upload.Part.FileMissing", "待上传文件不存在，取消上传/可能会触发重试");
        EVENT_ZH.put("Upload.Part.NoUploadUser", "房间未配置上传用户，无法上传");
        EVENT_ZH.put("Upload.Part.UploadUserMissing", "上传用户不存在，无法上传");
        EVENT_ZH.put("Upload.Part.LoginInvalid", "上传用户登录失效，无法上传");
        EVENT_ZH.put("Upload.PreUpload.Failed", "预上传失败(可能限流/风控/需验证码)");
        EVENT_ZH.put("Upload.Captcha.Required", "需要验证码验证，请在网页中处理");
        EVENT_ZH.put("Upload.Captcha.Timeout", "等待验证码超时，将按策略处理/重试");
        EVENT_ZH.put("Upload.RateLimit.Wait", "触发限流，等待后重试");
        EVENT_ZH.put("Upload.PreUpload.Success", "预上传成功，开始分片上传");
        EVENT_ZH.put("Upload.PreUpload.Fallback", "节点被B站降级或切换(当前区域不可用/已满)");
        EVENT_ZH.put("Upload.PreUpload.PreferUpcdn", "指定线路不可用，已优先切换到upcdn节点");
        EVENT_ZH.put("Upload.PreUpload.AvoidEsheep", "已主动避开esheep边缘节点，切换至其他可用节点");
        EVENT_ZH.put("Upload.PreUpload.EdgeProxy", "当前使用边缘/代理节点，上传速度可能不如预期");
        EVENT_ZH.put("Upload.PreUpload.Response", "预上传响应(调试)");
        EVENT_ZH.put("Upload.Chunk.Progress", "分片上传进度(调试)");
        EVENT_ZH.put("Upload.Chunk.FileMissing", "分片上传时文件不存在，终止");
        EVENT_ZH.put("Upload.Chunk.Error", "分片上传失败，将等待后重试");
        EVENT_ZH.put("Upload.Chunk.Retryable", "分片上传可重试，已进入等待重试");
        EVENT_ZH.put("Upload.Chunk.GlobalFuseOpen", "分片上传触发全局熔断，停止继续重试");
        EVENT_ZH.put("Upload.Chunk.AllFailed", "分片上传未完成，进入分P失败重试流程");
        EVENT_ZH.put("Upload.GatewayErrorPause", "捕获到网关错误，暂停任务一段时间");
        EVENT_ZH.put("Upload.Part.RetryScheduled", "上传失败，已安排重试");
        EVENT_ZH.put("Upload.Complete.Retry", "合并(complete)失败，重试中");
        EVENT_ZH.put("Upload.Complete.CheckRetry", "校验合并结果失败，重试中");
        EVENT_ZH.put("Upload.Complete.Wait", "等待合并完成后再继续");
        EVENT_ZH.put("Upload.Complete.WaitInterrupted", "等待合并完成的等待被中断");
        EVENT_ZH.put("Upload.Complete.Response", "合并响应(调试)");
        EVENT_ZH.put("Upload.Transcode.Response", "转码响应(调试)");
        EVENT_ZH.put("Upload.Post.DeleteSuccess", "上传后删除文件成功");
        EVENT_ZH.put("Upload.Post.DeleteFailed", "上传后删除文件失败");
        EVENT_ZH.put("Upload.Post.MoveSuccess", "上传后移动文件成功");
        EVENT_ZH.put("Upload.Post.MoveFailed", "上传后移动文件失败");
        EVENT_ZH.put("Upload.Part.Success", "分P上传成功");
        EVENT_ZH.put("Upload.Part.Failed", "分P上传失败");
        EVENT_ZH.put("Upload.SkipNotNeeded", "未开启上传或无需上传，跳过");
        EVENT_ZH.put("Upload.SkipBelowThreshold", "文件低于阈值(大小/时长)不上传，按策略处理");
        EVENT_ZH.put("Upload.ServiceError", "上传服务发生异常");
        EVENT_ZH.put("Upload.Concurrency", "分P上传：动态调整并发数");
        EVENT_ZH.put("Upload.FairShare", "分P上传：按活跃账号决定是否分摊限速");
        EVENT_ZH.put("Upload.FairShare.AccountState", "分P上传：活跃上传账号状态变更");
        EVENT_ZH.put("Upload.SerialScheduler.Enqueued", "分P上传串行调度：任务已入队");
        EVENT_ZH.put("Upload.SerialScheduler.Dispatch", "分P上传串行调度：开始执行队首任务");
        EVENT_ZH.put("Upload.SerialScheduler.Completed", "分P上传串行调度：任务执行完成");
        EVENT_ZH.put("Upload.SerialScheduler.Failed", "分P上传串行调度：任务执行失败");
        EVENT_ZH.put("Upload.SerialScheduler.TailRecovered", "分P上传串行调度：前置任务异常已隔离");
        EVENT_ZH.put("Upload.SerialScheduler.RequeueOnRejected", "分P上传串行调度：线程池拒绝，已兜底重排队");
        EVENT_ZH.put("Upload.SerialScheduler.RequeueGiveUp", "分P上传串行调度：重排队次数过多，放弃");
        EVENT_ZH.put("Upload.SerialScheduler.DuplicatePartSkipped", "分P上传串行调度：分P已在队列中，跳过重复入队");
        EVENT_ZH.put("Publish.Parts.DuplicatePhysicalFileFiltered", "投稿：检测到同一物理文件的重复分P，已按规则过滤");

        // 文件检测
        EVENT_ZH.put("FileProbe.Remove.Missing", "文件检测：文件丢失，移除监控");
        EVENT_ZH.put("FileProbe.Missing.Waiting", "文件检测：文件丢失但仍在等待(可能是网络波动)");
        EVENT_ZH.put("FileProbe.Start", "文件检测：开始监控");
        EVENT_ZH.put("FileProbe.Stable", "文件检测：文件已稳定");
        EVENT_ZH.put("FileProbe.Waiting", "文件检测：文件等待中");
        EVENT_ZH.put("FileProbe.SizeChanged", "文件检测：文件大小发生变化");

        // 录制事件
        EVENT_ZH.put("Room.AutoCreate", "房间不存在，自动创建房间配置");
        EVENT_ZH.put("Room.Seasons.FetchFailed", "房间合集列表获取失败(返回空列表)");
        EVENT_ZH.put("Room.Seasons.Fetch.Success", "房间合集列表获取成功");
        EVENT_ZH.put("Room.Update.SeasonSection.Fixed", "房间编辑保存：合集配置已校验并修正");
        EVENT_ZH.put("Room.Update.SeasonSection.Corrected", "房间编辑保存：section不属于season，已修正为首section");
        EVENT_ZH.put("Room.Update.SeasonSection.Disabled", "房间编辑保存：合集配置无效，已禁用合集");
        EVENT_ZH.put("RecordStarted.ReuseHistory", "录制开始：复用历史记录(继续录制)");
        EVENT_ZH.put("RecordStarted.ReuseHistory.Detail", "录制开始：复用历史记录明细(调试)");
        EVENT_ZH.put("RecordStarted.InvalidPayload", "录制开始：事件载荷缺少房间号");
        EVENT_ZH.put("RecordStarted.ReuseActiveHistory", "录制开始：复用活跃的历史记录");
        EVENT_ZH.put("RecordStarted.ReuseOpenPartHistory", "录制开始：复用仍有录制中分P的历史记录");
        EVENT_ZH.put("RecordStarted.ReuseRecentHistory", "录制开始：复用最近的历史记录");
        EVENT_ZH.put("RecordStarted.SkipForceArchivedActive", "录制开始：活跃历史已被强制归档，跳过复用");
        EVENT_ZH.put("RecordStarted.SkipForceArchived", "录制开始：历史已被强制归档，跳过复用");
        EVENT_ZH.put("RecordStarted.SkipPublished", "录制开始：发现活跃记录已发布/审核中，跳过复用并拆分新稿件");
        EVENT_ZH.put("RecordStarted.ActiveHistoryStale", "录制开始：检测到过时的录制中记录(24小时无活动)");
        EVENT_ZH.put("RecordStarted.Processed", "录制开始事件处理完成");
        EVENT_ZH.put("StreamEnd.IgnoredEmpty", "收到空的下播事件，忽略");
        EVENT_ZH.put("StreamEnd.Received", "收到下播事件");
        EVENT_ZH.put("StreamEnd.RoomMissing", "下播事件：房间不存在，忽略");
        EVENT_ZH.put("StreamEnd.NoRecording", "下播事件：本地无活跃录制记录");
        EVENT_ZH.put("RecordEnd.Received", "收到录制结束事件");
        EVENT_ZH.put("RecordEnd.NoRoom", "录制结束事件：房间不存在，忽略");
        EVENT_ZH.put("RecordEnd.IgnoreStaleSession", "录制结束事件：旧 session 结束事件，已忽略");
        EVENT_ZH.put("RecordEnd.NoRecording", "录制结束事件：本地无活跃录制记录");
        EVENT_ZH.put("RecordEnd.PartHeal.Done", "录制结束：分P纠偏/修复成功");
        EVENT_ZH.put("RecordEnd.PartHeal.Failed", "录制结束：分P纠偏/修复失败");
        EVENT_ZH.put("RecordEnd.ParseMergeIntervalFailed", "录制结束：解析合并间隔配置失败");
        EVENT_ZH.put("FileOpen.Received", "收到文件打开事件");
        EVENT_ZH.put("FileOpen.RoomNotFound.AutoCreate", "文件打开事件：房间不存在，自动创建");
        EVENT_ZH.put("FileOpen.FATAL.HistoryStillNotFound", "文件打开事件：致命错误，自愈后仍未找到录制历史");
        EVENT_ZH.put("FileOpen.PartSaved", "文件打开事件：成功创建并保存新的分P记录");
        EVENT_ZH.put("FileOpen.PartExists.Skip", "文件打开事件：分P记录已存在，跳过重复创建");
        EVENT_ZH.put("SessionMismatch.Detected", "检测到 Session ID 不匹配(可能错过了[录制开始]事件)(调试)");
        EVENT_ZH.put("SessionMismatch.Merged", "Session ID 不匹配：检测到最近的直播记录，已自动合并");
        EVENT_ZH.put("SessionMismatch.ReuseActiveHistory", "Session ID 不匹配：复用活跃的历史记录");
        EVENT_ZH.put("SessionMismatch.ReuseOpenPartHistory", "Session ID 不匹配：复用仍有录制中分P的历史记录");
        EVENT_ZH.put("SessionMismatch.ReuseRecentHistory", "Session ID 不匹配：按合并间隔复用最近历史记录");
        EVENT_ZH.put("SessionMismatch.SkipPublished", "Session ID 不匹配：发现最近记录已发布/审核中，跳过合并并创建新稿件");
        EVENT_ZH.put("SessionMismatch.SkipForceArchived", "Session ID 不匹配：历史已被强制归档，跳过复用");
        EVENT_ZH.put("SessionMismatch.ActiveHistoryStale", "Session ID 不匹配：检测到过时的录制中记录(24小时无活动)");
        EVENT_ZH.put("SessionMismatch.CreatedNew", "Session ID 不匹配：未找到最近记录，已创建新的录制历史");
        EVENT_ZH.put("SessionMismatch.ParseMergeIntervalConfigFailed", "Session ID 不匹配：解析合并间隔配置失败");
        EVENT_ZH.put("SessionMismatch.ParseMergeIntervalFailed", "Session ID 不匹配：解析合并间隔配置失败");
        EVENT_ZH.put("PublishJob.PublishHistory.Error", "执行投稿任务异常");
        EVENT_ZH.put("SessionMismatch.Recovered", "Session ID 不匹配：自愈成功，已更新状态");
        EVENT_ZH.put("FileOpen.MissingRoom", "文件打开事件：房间不存在");
        EVENT_ZH.put("FileOpen.HistoryRoomMismatch", "文件打开事件：history 与 room 不匹配");
        EVENT_ZH.put("FileOpen.MissingHistory", "文件打开事件：找不到录制历史");
        EVENT_ZH.put("FileOpen.PartLimitReached", "分P数量达到上限，忽略新分P");
        EVENT_ZH.put("FileOpen.PartExists", "分P已存在，忽略重复创建");
        EVENT_ZH.put("FileOpen.Saved", "分P记录已保存");
        EVENT_ZH.put("FileOpen.DebugPart", "分P调试信息");
        EVENT_ZH.put("FileOpen.SleepInterrupted", "文件打开事件等待被中断(通常不影响主流程)");
        EVENT_ZH.put("FileOpen.UnhandledException", "文件打开事件：发生未捕获异常");
        EVENT_ZH.put("FileClosed", "收到文件关闭事件(分P文件写入结束)");
        EVENT_ZH.put("FileClosed.NoRecording", "文件关闭事件：本地无活跃录制记录");
        EVENT_ZH.put("FileClosed.PartMatchedNormalizedPath", "文件关闭事件：按规范化路径命中已有分P记录");
        EVENT_ZH.put("FileClosed.PartMissing", "文件关闭事件：找不到分P记录");
        EVENT_ZH.put("FileClosed.FileMissing", "文件关闭事件：文件不存在");
        EVENT_ZH.put("FileClosed.MoveSuccess", "文件关闭后移动文件成功");
        EVENT_ZH.put("FileClosed.MoveFailed", "文件关闭后移动文件失败");
        EVENT_ZH.put("FileClosed.CopySuccess", "文件关闭后复制文件成功");
        EVENT_ZH.put("FileClosed.CopyFailed", "文件关闭后复制文件失败");
        EVENT_ZH.put("FileClosed.MissingHistory", "文件关闭事件：找不到录制历史");
        EVENT_ZH.put("FileClosed.HistoryRecovered.ByPart", "文件关闭事件：按分P记录自愈并修正historyId");
        EVENT_ZH.put("FileClosed.HistoryRecovered.ByActiveHistory", "文件关闭事件：复用活跃history自愈并修正historyId");

        // 分P补救
        EVENT_ZH.put("PartRepair.Rescan.Done", "分P补救：重试扫描完成");
        EVENT_ZH.put("PartRepair.Rescan.UploadTriggerFailed", "分P补救：重试扫描触发上传失败");
        EVENT_ZH.put("PartRepair.MarkFinished", "分P补救：已标记结束/跳过");
        EVENT_ZH.put("PartRepair.BindFile.Done", "分P补救：补全文件完成");
        EVENT_ZH.put("PartRepair.BindFile.UploadTriggerFailed", "分P补救：补全文件触发上传失败");
        EVENT_ZH.put("FilePost.Received", "收到文件后处理事件");
        EVENT_ZH.put("FilePost.PartMissing", "文件后处理：找不到分P记录");
        EVENT_ZH.put("FilePost.FileMissing", "文件后处理：文件不存在");
        EVENT_ZH.put("FilePost.Saved", "文件后处理：保存/更新成功");

        // 投稿(部分)
        EVENT_ZH.put("Publish.UploadUserMissing", "投稿：上传用户不存在");
        EVENT_ZH.put("Publish.LoginInvalid", "投稿：上传用户登录失效");
        EVENT_ZH.put("Publish.TimestampJump.GiveUpPart", "投稿：检测到时间戳跳变，放弃该分P");
        EVENT_ZH.put("Publish.TimestampJump.SkipReupload", "投稿：时间戳跳变，跳过重新上传");
        EVENT_ZH.put("Publish.TimestampJump.MarkPartGiveUp", "投稿：标记分P为时间戳跳变放弃");
        EVENT_ZH.put("Publish.PartUploadLock.Acquired", "投稿：获取分P上传锁成功");
        EVENT_ZH.put("Publish.Part.NotUploaded", "投稿：分P未上传完成，等待/触发上传");
        EVENT_ZH.put("Publish.PartUpload.WaitQueued", "投稿流程：分P已在队列中，等待现有上传结果");
        EVENT_ZH.put("Publish.PartUpload.WaitTimeout", "投稿流程：等待队列中分P上传超时");
        EVENT_ZH.put("Publish.PartUpload.Deferred", "投稿流程：分P仍在上传，本轮投稿延后");
        EVENT_ZH.put("Publish.Republish.Response", "投稿：重新投稿响应");
        EVENT_ZH.put("Publish.Edit.Start", "投稿编辑：开始编辑已投稿稿件");
        EVENT_ZH.put("Publish.Edit.Response", "投稿编辑：编辑稿件接口响应");
        EVENT_ZH.put("Publish.Edit.SkipNoAid", "投稿编辑：缺少 aid，跳过编辑");
        EVENT_ZH.put("Publish.Edit.PartUpload", "投稿编辑：补上传分P");
        EVENT_ZH.put("Publish.Edit.Deferred", "投稿编辑：分P仍在上传，延后编辑");
        EVENT_ZH.put("Publish.Edit.Error", "投稿编辑：编辑流程异常");
        EVENT_ZH.put("Publish.Edit.RoomMissing", "投稿编辑：房间配置不存在");
        EVENT_ZH.put("Publish.Edit.Parts.Empty", "投稿编辑：可提交分P为空");
        EVENT_ZH.put("Publish.Edit.PartGiveUpTimestampJump", "投稿编辑：时间戳跳变分P放弃重传");
        EVENT_ZH.put("Publish.Edit.PartUpload.SkipNoFilePath", "投稿编辑：分P文件路径为空，跳过上传");
        EVENT_ZH.put("Publish.Edit.PartUpload.SkipFileMissing", "投稿编辑：分P文件不存在，跳过上传");
        EVENT_ZH.put("Publish.Edit.OnlinePartInfo.Empty", "投稿编辑：线上分P信息为空");
        EVENT_ZH.put("Publish.Edit.OnlinePartInfo.Failed", "投稿编辑：获取线上分P信息失败");
        EVENT_ZH.put("Publish.Edit.VideoList.BlockedPart", "投稿编辑：分P无法安全合并到编辑列表");
        EVENT_ZH.put("Publish.EditParts.TempUploadFailed", "分P编辑：临时文件上传失败");
        EVENT_ZH.put("Publish.EditParts.TempCleanupFailed", "分P编辑：临时文件清理失败");
        EVENT_ZH.put("Publish.EditParts.SubmitFailed", "分P编辑：提交编辑失败");
        EVENT_ZH.put("Publish.EditParts.TempPartDeleteFailed", "分P编辑：临时分P删除失败");
        EVENT_ZH.put("Publish.EditParts.SyncStatusFailed", "分P编辑：编辑后刷新稿件状态失败");
        EVENT_ZH.put("Publish.Task.SuspendedSkip", "投稿任务已暂停，跳过本次执行");
        EVENT_ZH.put("Publish.History.AlreadyPublished", "投稿历史已标记为发布，跳过");
        EVENT_ZH.put("Publish.Retry.GiveUp", "投稿重试次数过多，放弃");
        EVENT_ZH.put("Publish.Task.AlreadyRunning", "投稿任务正在运行中，跳过重复触发");
        EVENT_ZH.put("Publish.Start", "投稿流程开始");
        EVENT_ZH.put("Publish.Room.TidMissing", "投稿失败：未设置分区(tid)");
        EVENT_ZH.put("Publish.Parts.Empty", "投稿失败：分P列表为空");
        EVENT_ZH.put("Publish.Parts.TooMany.Split", "投稿异常：分P数量过多，尝试分次投稿");
        EVENT_ZH.put("Publish.Part.StillRecording", "投稿失败：存在仍在录制中的分P");
        EVENT_ZH.put("Publish.Part.FileStillWriting", "投稿：检测到文件仍在写入/未稳定，暂停本次投稿");
        EVENT_ZH.put("Publish.Part.FilePathInvalid", "投稿异常：分P文件路径无效");
        EVENT_ZH.put("Publish.Part.EndTimeSuspicious", "投稿异常：分P结束时间异常(可能时间不同步)");
        EVENT_ZH.put("Publish.Part.SkipBelowSizeLimit", "投稿：分P文件小于忽略大小阈值，删除记录");
        EVENT_ZH.put("Publish.Part.SkipBelowDurationLimit", "投稿：分P时长小于忽略时长阈值，删除记录");
        EVENT_ZH.put("Publish.PartUploadLock.Wait", "投稿：等待分P上传锁");
        EVENT_ZH.put("Publish.Part.Uploaded.WaitCooldown", "投稿：分P上传后等待冷却，避免频繁请求");
        EVENT_ZH.put("Publish.Part.Uploaded.WaitCooldownInterrupted", "投稿：冷却等待被中断");
        EVENT_ZH.put("Publish.GatewayErrorPause", "投稿：捕获到网关错误，暂停任务一段时间");
        EVENT_ZH.put("Publish.Parts.Changed", "投稿失败：分P数量发生变动");
        EVENT_ZH.put("Publish.Parts.NotAllUploaded", "投稿失败：存在未上传完成的分P");
        EVENT_ZH.put("PublishJob.Skip.ForceArchived", "投稿任务：稿件已强制归档，跳过自动投稿");
        EVENT_ZH.put("Publish.UploadUserIdMissing", "投稿失败：未配置上传用户ID");
        EVENT_ZH.put("Publish.Cover.Upload.Response", "投稿：封面上传响应(调试)");
        EVENT_ZH.put("Publish.Cover.Upload.Failed", "投稿：上传/使用直播封面失败");
        EVENT_ZH.put("Publish.Cover.NotFound", "投稿：封面文件未找到，尝试使用直播间当前封面兜底");
        EVENT_ZH.put("Publish.Cover.Fallback.Success", "投稿：兜底封面获取成功");
        EVENT_ZH.put("Publish.Cover.Fallback.Failed", "投稿：兜底封面获取失败");
        EVENT_ZH.put("Publish.Cover.Upload.Retry", "投稿：封面上传失败，正在重试");
        EVENT_ZH.put("Publish.WebPublish.UploadPartsReady", "投稿：提交前分P上传来源摘要");
        EVENT_ZH.put("Publish.WebPublish.PayloadDebug", "投稿：Web端提交参数摘要(调试)");
        EVENT_ZH.put("Publish.WebPublish.Response", "投稿：Web端提交响应(调试)");
        EVENT_ZH.put("Publish.Captcha.Submit", "投稿：提交验证码结果");
        EVENT_ZH.put("Publish.Captcha.PublishResponse", "投稿：验证码投稿响应(调试)");
        EVENT_ZH.put("Publish.Captcha.VerifyFailedPause", "投稿：验证码验证失败，暂停后重试");
        EVENT_ZH.put("Publish.Captcha.HandleError", "投稿：处理验证码流程异常");
        EVENT_ZH.put("Publish.TimestampJump.GiveUpHistory", "投稿：检测到时间戳跳变，放弃该投稿");
        EVENT_ZH.put("Publish.WebPublish.MissingIds", "投稿失败：响应缺少 bvid/aid");
        EVENT_ZH.put("Publish.WebPublish.Success", "投稿成功");
        EVENT_ZH.put("Publish.Visibility.Sync.Success", "投稿后同步视频可见性成功");
        EVENT_ZH.put("Publish.Visibility.Sync.SkipInvalid", "投稿后同步视频可见性跳过(配置非法)");
        EVENT_ZH.put("Publish.Visibility.Sync.Failed", "投稿后同步视频可见性失败");
        EVENT_ZH.put("Publish.Season.Section.Corrected", "投稿前校验合集配置：section已修正");
        EVENT_ZH.put("Publish.Season.Section.Disabled", "投稿前校验合集配置：已禁用合集");
        EVENT_ZH.put("Publish.Season.Add.Success", "投稿后加入合集成功");
        EVENT_ZH.put("Publish.Season.Add.Failed", "投稿后加入合集失败");
        EVENT_ZH.put("Publish.File.DeleteSuccess", "投稿后删除文件成功");
        EVENT_ZH.put("Publish.File.DeleteFailed", "投稿后删除文件失败");
        EVENT_ZH.put("Publish.File.MoveSuccess", "投稿后移动文件成功");
        EVENT_ZH.put("Publish.File.MoveFailed", "投稿后移动文件失败");
        EVENT_ZH.put("Publish.File.PostProcess.Error", "投稿成功后文件处理(删除/移动)异常");
        EVENT_ZH.put("Publish.WebPublish.Failed", "投稿失败(异常/返回异常)");
        EVENT_ZH.put("Publish.Error", "投稿流程发生异常");

        // 模板
        EVENT_ZH.put("Template.UserCard.FetchFailed", "简介模板：获取@用户信息失败");
        EVENT_ZH.put("Template.AtUser.Failed", "简介模板：处理@用户失败");
        EVENT_ZH.put("Template.DateFormat.Failed", "简介模板：时间格式化失败");

        // Webhook
        EVENT_ZH.put("Webhook.CleanupError", "Webhook 清理/回收发生异常");
        EVENT_ZH.put("Webhook.Received", "收到 Webhook 事件");
        EVENT_ZH.put("Webhook.ReceivedLegacy", "收到旧版 Webhook 事件");
        EVENT_ZH.put("Webhook.ProcessFailed", "Webhook 处理失败");
        EVENT_ZH.put("Webhook.Payload.Debug", "Webhook payload 调试信息(不含全文)");
        EVENT_ZH.put("Webhook.LockKey.BuildFailed", "Webhook 构建锁 key 失败");
        EVENT_ZH.put("Webhook.InvalidPayload", "Webhook 请求体无效(缺少关键字段)");

        // HTTP
        EVENT_ZH.put("Netty.Upload.Timeout", "Netty上传：连接/传输超时");
        EVENT_ZH.put("Netty.Upload.Failed", "Netty上传：连接/传输失败");
        EVENT_ZH.put("Netty.Upload.LowSpeed", "Netty上传：传输速度过慢，已强制断开");
        EVENT_ZH.put("Netty.Upload.RateLimitedExpected", "Netty上传：当前速度符合限速预期，继续传输");
        EVENT_ZH.put("ImageProxy.InvalidHost", "图片代理：Host不合法");
        EVENT_ZH.put("ImageProxy.InvalidScheme", "图片代理：Scheme不合法");
        EVENT_ZH.put("ImageProxy.Failed", "图片代理：请求失败");
        EVENT_ZH.put("Http.Request.Failed", "HTTP 请求失败(网络/超时/连接问题)");
        EVENT_ZH.put("Http.Notify.ServerChan3.Request.Failed", "Server酱3通知发送失败(网络/超时/连接问题)");
        EVENT_ZH.put("Http.Response.Error", "HTTP 响应非 2xx(服务端错误/被拦截/参数异常)");
        EVENT_ZH.put("Http.RiskControl.Triggered", "检测到风控(-412)，可能需要降低频率或更换网络");
        EVENT_ZH.put("Http.Dns.UnknownHost.Retry", "域名解析失败，等待后重试");
        EVENT_ZH.put("Http.Get.Failed", "HTTP GET 请求失败");

        // 事件分发
        EVENT_ZH.put("RecordEvent.TypeMissing", "事件类型为空，无法分发到处理器");
        EVENT_ZH.put("RecordEvent.Unsupported", "不支持的事件类型，已进入兜底处理器");

        // 用户token刷新
        EVENT_ZH.put("User.RefreshToken.Success", "刷新 token 成功");
        EVENT_ZH.put("User.RefreshToken.MyInfoFailed", "刷新 token 后获取用户信息失败(不影响 token 更新)");
        EVENT_ZH.put("User.RefreshToken.FailedButUsable", "刷新 token 失败，但账号仍可用(沿用旧 token)");
        EVENT_ZH.put("User.RefreshToken.FailedAndMyInfoFailed", "刷新 token 失败，且获取用户信息也失败");
        EVENT_ZH.put("User.RefreshToken.Failed", "刷新 token 失败，账号将被禁用/标记为不可用");

        // 用户登录
        EVENT_ZH.put("BiliUser.LoginQr.Generate.Success", "用户登录：二维码生成成功");
        EVENT_ZH.put("BiliUser.LoginQr.Generate.Failed", "用户登录：二维码生成失败");
        EVENT_ZH.put("BiliUser.Login.Success", "用户登录成功");
        EVENT_ZH.put("BiliUser.LoginCheck.Error", "检查登录状态异常");

        // 哔哩哔哩API
        EVENT_ZH.put("BiliApi.DeviceIds.LoadOrSaveFailed", "加载/保存设备标识失败，将使用随机设备标识");
        EVENT_ZH.put("BiliApi.QrUrl.Generated", "生成扫码登录 URL(调试)");
        EVENT_ZH.put("BiliApi.Sign.UrlEncodeFailed", "签名计算：URL 编码失败");
        EVENT_ZH.put("BiliApi.Rsa.Failed", "RSA 加密失败");

        // SSL
        EVENT_ZH.put("HttpsTrustManager.AllowAllSSL.Failed", "SSL：允许所有证书初始化失败");
        EVENT_ZH.put("HttpsTrustManager.CreateSocketFactory.Failed", "SSL：创建 SocketFactory 失败");

        // 定时任务
        EVENT_ZH.put("DeletePartFileJob.Start", "定时任务：开始清理分P文件");
        EVENT_ZH.put("DeletePartFileJob.SkipBlankPath", "定时任务：分P文件路径为空，已标记跳过");
        EVENT_ZH.put("DeletePartFileJob.SkipNotExists", "定时任务：文件不存在，跳过");
        EVENT_ZH.put("DeletePartFileJob.Success", "定时任务：删除成功");
        EVENT_ZH.put("DeletePartFileJob.FailedRetry", "定时任务：删除失败，准备重试");
        EVENT_ZH.put("DeletePartFileJob.FailedGiveUp", "定时任务：删除失败，已放弃");
        EVENT_ZH.put("DeletePartFileJob.Round.Done", "定时任务：分P文件删除轮次完成");
        EVENT_ZH.put("MovePartFileJob.Start", "定时任务：开始移动分P文件");
        EVENT_ZH.put("MovePartFileJob.SkipBlankPath", "定时任务：分P文件路径为空，已标记跳过");
        EVENT_ZH.put("MovePartFileJob.Success", "定时任务：移动成功");
        EVENT_ZH.put("MovePartFileJob.Failed", "定时任务：移动失败");
        EVENT_ZH.put("MovePartFileJob.Round.Done", "定时任务：分P文件移动轮次完成");
        EVENT_ZH.put("PartFileCleanup.SkipProtectedArchive", "分P文件清理：稿件已退回/锁定/强制归档，跳过删除或移动");

        EVENT_ZH.put("RefreshTokenJob.SkipRecent", "定时任务：刷新token-距离上次更新过近，跳过");
        EVENT_ZH.put("RefreshTokenJob.SleepInterrupted", "定时任务：刷新token-等待被中断");
        EVENT_ZH.put("RefreshTokenJob.RefreshFailed", "定时任务：刷新token失败");

        EVENT_ZH.put("BrecCookieSync.SkipIncompleteConfig", "定时任务：录播姬Cookie同步-配置不完整，跳过");
        EVENT_ZH.put("BrecCookieSync.InvalidUid", "定时任务：录播姬Cookie同步-UID格式无效");
        EVENT_ZH.put("BrecCookieSync.NoUsableCookie", "定时任务：录播姬Cookie同步-未找到可用账号或Cookie");
        EVENT_ZH.put("BrecCookieSync.EmptyCookieAfterConvert", "定时任务：录播姬Cookie同步-Cookie转换后为空");
        EVENT_ZH.put("BrecCookieSync.HttpError", "定时任务：录播姬Cookie同步-录播姬返回错误状态");
        EVENT_ZH.put("BrecCookieSync.AuthFailed", "定时任务：录播姬Cookie同步-认证失败");
        EVENT_ZH.put("BrecCookieSync.Unreachable", "定时任务：录播姬Cookie同步-无法访问录播姬");
        EVENT_ZH.put("BrecCookieSync.RequestFailed", "定时任务：录播姬Cookie同步-请求录播姬失败");
        EVENT_ZH.put("BrecCookieSync.Success", "定时任务：录播姬Cookie同步成功");

        EVENT_ZH.put("RoomStatusSyncJob.Start", "定时任务：直播间状态同步开始(兜底机制)");
        EVENT_ZH.put("RoomStatusSyncJob.StreamingChanged", "定时任务：直播间直播状态发生变化");
        EVENT_ZH.put("RoomStatusSyncJob.ForceResetRecording", "定时任务：直播结束，强制重置录制状态");
        EVENT_ZH.put("RoomStatusSyncJob.ForceResetHistory", "定时任务：直播结束，强制重置历史记录状态");
        EVENT_ZH.put("RoomStatusSyncJob.HistoryRoomMismatch", "定时任务：检测到房间绑定的历史记录不匹配，已清理指针并跳过重置");
        EVENT_ZH.put("RoomStatusSyncJob.TitleChanged", "定时任务：直播间标题发生变化(调试)");
        EVENT_ZH.put("RoomStatusSyncJob.SleepInterrupted", "定时任务：直播间状态同步-等待被中断");
        EVENT_ZH.put("RoomStatusSyncJob.Failed", "定时任务：直播间状态同步失败");
        EVENT_ZH.put("RoomStatusSyncJob.Done", "定时任务：直播间状态同步完成");
        EVENT_ZH.put("VideoSync.Round.Done", "定时任务：视频状态同步轮次完成");

        EVENT_ZH.put("Auth.Basic.Enabled", "已启用 Basic 认证");
        EVENT_ZH.put("Auth.Basic.DisabledByConfig", "未配置用户名或密码，Basic 认证已禁用(存在安全风险)");
        EVENT_ZH.put("Auth.Basic.Failed", "Basic 认证失败");

        // 投稿定时任务
        EVENT_ZH.put("PublishJob.PendingCount", "定时任务：待发布稿件数量");
        EVENT_ZH.put("PublishJob.ActuallyRecordingParts.CountFailed", "定时任务：统计录制中分P失败，跳过投稿");
        EVENT_ZH.put("PublishJob.PartRecording.Healed", "定时任务：纠偏分P录制状态");
        EVENT_ZH.put("PublishJob.PartRecording.RecountFailed", "定时任务：纠偏后再次统计失败，跳过投稿");
        EVENT_ZH.put("PublishJob.PartRecording.SuspectPart", "定时任务：疑似录制中分P详情(调试)");
        EVENT_ZH.put("PublishJob.ParseMergeIntervalConfigFailed", "定时任务：解析合并间隔配置失败");
        EVENT_ZH.put("PublishJob.Skip.HasRecordingParts", "定时任务：存在录制中分P，跳过投稿");
        EVENT_ZH.put("PublishJob.Skip.HistoryStreaming", "定时任务：直播中，跳过投稿");
        EVENT_ZH.put("PublishJob.Skip.FileStillWriting", "定时任务：检测到文件仍在写入/未稳定，跳过投稿");
        EVENT_ZH.put("PublishJob.WaitNext", "定时任务：本轮投稿结束，等待下一轮");
        EVENT_ZH.put("PublishJob.WaitNextInterrupted", "定时任务：等待下一轮被中断");
        EVENT_ZH.put("PublishJob.Round.Done", "定时任务：投稿轮次完成");

        EVENT_ZH.put("PublishJob.PartCompensate.AlreadyUploading", "补偿任务：分P正在上传中，跳过");
        EVENT_ZH.put("PublishJob.PartCompensate.AlreadyQueued", "补偿任务：分P已在队列中，跳过");
        EVENT_ZH.put("PublishJob.PartCompensate.FilePathMissing", "补偿任务：文件路径为空，放弃上传");
        EVENT_ZH.put("PublishJob.PartCompensate.FileMissing", "补偿任务：文件丢失，放弃上传");
        EVENT_ZH.put("PublishJob.PartCompensate.Skip.FileStillWriting", "补偿任务：文件仍在写入/未稳定，跳过");
        EVENT_ZH.put("PublishJob.PartCompensate.FileSizeUnreadableRetryLater", "补偿任务：文件大小无法读取，稍后重试");
        EVENT_ZH.put("PublishJob.PartCompensate.DurationUnreadableRetryLater", "补偿任务：文件时长无法读取，稍后重试");
        EVENT_ZH.put("PublishJob.PartCompensate.SkipBelowSizeLimit", "补偿任务：文件小于大小阈值，跳过");
        EVENT_ZH.put("PublishJob.PartCompensate.SkipBelowDurationLimit", "补偿任务：文件小于时长阈值，跳过");
        EVENT_ZH.put("PublishJob.PartCompensate.TriggerUpload", "补偿任务：触发分P上传");
        EVENT_ZH.put("PublishJob.PartCompensate.DuplicateRejected", "补偿任务：分P重复触发被去重拒绝");
        EVENT_ZH.put("PublishJob.PartCompensate.DuplicatePhysicalFileSkipped", "补偿任务：同一物理文件已有更优分P，跳过重复上传");
        EVENT_ZH.put("PublishJob.PartCompensate.UploadFailed", "补偿任务：触发上传失败");
        EVENT_ZH.put("PublishJob.PartCompensate.UploadRejectedRetryLater", "补偿任务：线程池拥塞导致触发被拒，稍后重试");
        EVENT_ZH.put("PublishJob.PartCompensate.SkipByAsyncPoolPressure", "补偿任务：线程池压力过高，跳过本轮触发");
        EVENT_ZH.put("PublishJob.PartCompensate.SkipByRoundQuota", "补偿任务：触发数达到本轮上限，停止继续触发");
        EVENT_ZH.put("PublishJob.PartCompensate.SkipByUserQuota", "补偿任务：账号触发数达到上限，跳过");
        EVENT_ZH.put("PublishJob.PartCompensate.ThrottleSleepInterrupted", "补偿任务：并发控制等待被中断");
        EVENT_ZH.put("PublishJob.PartCompensate.TriggeredSummary", "补偿任务：本轮触发统计");
        EVENT_ZH.put("PublishJob.PartCompensate.Round.Done", "补偿任务：轮次完成");

        // 视频状态同步
        EVENT_ZH.put("VideoSync.SleepInterrupted", "定时任务：视频状态同步-请求间隔等待被中断");
        EVENT_ZH.put("VideoSync.RoomMissing", "视频状态同步：未找到房间信息");
        EVENT_ZH.put("VideoSync.VideoInfo.Failed", "视频状态同步：获取视频信息失败");
        EVENT_ZH.put("VideoSync.NotVisibleStop", "视频状态同步：稿件不可见，停止同步");
        EVENT_ZH.put("VideoSync.DeletedConfirmed", "视频状态同步：已确认删除");
        EVENT_ZH.put("VideoSync.Confirm.Success", "视频状态同步：二次确认稿件状态成功");
        EVENT_ZH.put("VideoSync.Confirm.Failed", "视频状态同步：二次确认稿件状态失败");
        EVENT_ZH.put("VideoSync.StateFallback.OnlySelfByRoomConfig", "视频状态同步：无法确认状态，按房间配置仅自己可见处理");
        EVENT_ZH.put("VideoSync.StateFallback.KeepOld", "视频状态同步：无法确认状态，保持原状态");
        EVENT_ZH.put("VideoSync.KeepPendingAfterRecentEdit", "视频状态同步：编辑后短时间内保持审核中状态");
        EVENT_ZH.put("VideoSync.MemberApi.Unexpected", "视频状态同步：Member API 返回异常 code");
        EVENT_ZH.put("VideoSync.Confirm.SkipNoUser", "视频状态同步：未配置上传用户，跳过二次确认");
        EVENT_ZH.put("VideoSync.PartSynced", "视频状态同步：同步分P信息成功");
        EVENT_ZH.put("VideoSync.File.DeleteSuccess", "视频状态同步：删除文件成功");
        EVENT_ZH.put("VideoSync.File.DeleteFailed", "视频状态同步：删除文件失败");
        EVENT_ZH.put("VideoSync.File.MoveSuccess", "视频状态同步：移动文件成功");
        EVENT_ZH.put("VideoSync.File.MoveFailed", "视频状态同步：移动文件失败");
        EVENT_ZH.put("VideoSync.File.CopySuccess", "视频状态同步：复制文件成功");
        EVENT_ZH.put("VideoSync.File.CopyFailed", "视频状态同步：复制文件失败");
        EVENT_ZH.put("VideoSync.Part.ExceptionCleared", "视频状态同步：分P缺失CID异常已清除");
        EVENT_ZH.put("Stats.RefreshRecent.Failed", "统计刷新：最近历史补算失败");
        EVENT_ZH.put("Stats.RefreshHistory.Failed", "统计刷新：单场历史补算失败");
        EVENT_ZH.put("Stats.Aggregate.SkipActive", "统计聚合：跳过仍在录制或直播中的历史");

        // 弹幕发送同步
        EVENT_ZH.put("LiveMsgSendSync.Start", "定时任务：弹幕/评论发送开始");
        EVENT_ZH.put("LiveMsgSendSync.PrivateFlow.Detected", "检测到仅自己可见稿件，进入私有稿件评论流程");
        EVENT_ZH.put("LiveMsgSendSync.Visibility.SwitchPublic.Response", "私有稿件评论流程：切换公开响应");
        EVENT_ZH.put("LiveMsgSendSync.Visibility.SwitchPublic.Failed", "私有稿件评论流程：切换公开失败，停止后续操作");
        EVENT_ZH.put("LiveMsgSendSync.Visibility.SwitchPublic.ResponseParseFailed", "私有稿件评论流程：解析切换公开响应失败");
        EVENT_ZH.put("LiveMsgSendSync.PrivateFlow.SkipByError", "私有稿件评论流程：异常，跳过该视频处理");
        EVENT_ZH.put("LiveMsgSendSync.Reply.Send.Success", "视频评论发送成功");
        EVENT_ZH.put("LiveMsgSendSync.Reply.Send.Failed", "视频评论发送失败");
        EVENT_ZH.put("LiveMsgSendSync.Reply.Top.Failed", "视频评论置顶失败");
        EVENT_ZH.put("LiveMsgSendSync.Reply.BatchFailed", "SC/上舰评论批量发送失败");
        EVENT_ZH.put("LiveMsgSendSync.Reply.None", "没有需要发送的评论");
        EVENT_ZH.put("LiveMsgSendSync.GiftReply.Scan", "视频评论发送：礼物评论扫描结果");
        EVENT_ZH.put("LiveMsgSendSync.Reply.SkipByRoomConfig", "视频评论发送：按房间配置跳过(SC/礼物评论关闭)");
        EVENT_ZH.put("LiveMsgSendSync.Reply.SkipNoUploadUser", "视频评论发送：缺少可用上传账号，跳过");
        EVENT_ZH.put("LiveMsgSendSync.Reply.GlobalCooldown", "视频评论发送：等待全局评论间隔");
        EVENT_ZH.put("LiveMsgSendSync.Reply.AccountCooldown", "视频评论发送：等待投稿账号冷却");
        EVENT_ZH.put("LiveMsgSendSync.Reply.AccountPause", "视频评论发送：投稿账号进入冷却");
        EVENT_ZH.put("LiveMsgSendSync.Visibility.SwitchPrivate.Response", "私有稿件评论流程：切回仅自己可见响应");
        EVENT_ZH.put("LiveMsgSendSync.Visibility.SwitchPrivate.Failed", "私有稿件评论流程：切回仅自己可见失败");
        EVENT_ZH.put("LiveMsgSendSync.Visibility.SwitchPrivate.Error", "私有稿件评论流程：切回仅自己可见异常");
        EVENT_ZH.put("LiveMsgSendSync.SleepInterrupted", "弹幕/评论发送：等待被中断");
        EVENT_ZH.put("LiveMsgSendSync.Lock.Failed", "弹幕发送：获取锁失败");
        EVENT_ZH.put("LiveMsgSendSync.Part.SkipMissingCid", "分P缺失CID，跳过弹幕发送");
        EVENT_ZH.put("LiveMsgSendSync.UploadUser.InvalidState", "弹幕/评论发送：上传账号不可用(未登录或缺少UID)");
        EVENT_ZH.put("LiveMsgSendSync.AllDmDisabled.Archive", "弹幕/评论发送：房间普通弹幕/SC/礼物评论全关闭，直接归档");
        EVENT_ZH.put("LiveMsgSendSync.PendingPart.Empty", "弹幕/评论发送：没有待处理分P");
        EVENT_ZH.put("LiveMsgSendSync.HighLevel.Start", "高级弹幕发送开始");
        EVENT_ZH.put("LiveMsgSendSync.HighLevel.SkipByManual", "手动跳过高级弹幕发送");
        EVENT_ZH.put("LiveMsgSendSync.HighLevel.Send.Failed", "高级弹幕发送失败");
        EVENT_ZH.put("LiveMsgSendSync.HighLevel.RateLimit.Pause", "高级弹幕发送频控，暂停一段时间");
        EVENT_ZH.put("DanmakuDispatch.High.GlobalCooldown", "高级弹幕发送：等待全局弹幕间隔");
        EVENT_ZH.put("DanmakuDispatch.High.UserCooldown", "高级弹幕发送：等待投稿账号冷却");
        EVENT_ZH.put("LiveMsgSendSync.Normal.EmptyExit", "普通弹幕待发送为0，退出任务");
        EVENT_ZH.put("LiveMsgSendSync.Normal.Start", "普通弹幕发送开始");
        EVENT_ZH.put("LiveMsgSendSync.Normal.SkipByManual", "手动跳过普通弹幕发送");
        EVENT_ZH.put("LiveMsgSendSync.Normal.TimeLimitStop", "普通弹幕发送超时(超过上限)，停止本次任务");
        EVENT_ZH.put("LiveMsgSendSync.Normal.QueuePollInterrupted", "普通弹幕发送：队列取消息被中断");
        EVENT_ZH.put("LiveMsgSendSync.User.InvalidState", "弹幕发送：用户未登录或未启用");
        EVENT_ZH.put("LiveMsgSendSync.Normal.RateLimit.RetryOnce", "普通弹幕发送频控，暂停后重试一次");
        EVENT_ZH.put("LiveMsgSendSync.Normal.RateLimit.Pause", "普通弹幕发送频控过快，暂停一段时间");
        EVENT_ZH.put("LiveMsgSendSync.Normal.Send.InvalidTime", "普通弹幕发送失败：时间不合法");
        EVENT_ZH.put("LiveMsgSendSync.Normal.Send.VideoNotApproved", "普通弹幕发送失败：视频未审核通过");
        EVENT_ZH.put("LiveMsgSendSync.Normal.Send.UserDisabled", "普通弹幕发送失败：账号异常，已禁用该用户");
        EVENT_ZH.put("LiveMsgSendSync.Normal.Send.Failed", "普通弹幕发送失败");
        EVENT_ZH.put("DanmakuDispatch.Normal.GlobalCooldown", "普通弹幕发送：等待全局弹幕间隔");
        EVENT_ZH.put("LiveMsgSendSync.Done", "定时任务：弹幕/评论发送完成");

        // 弹幕
        EVENT_ZH.put("LiveMsg.Send.EmptyResponse", "弹幕发送：响应为空");
        EVENT_ZH.put("LiveMsg.Send.Failed", "弹幕发送：请求失败");
        EVENT_ZH.put("LiveMsg.Send.Error", "弹幕发送：发生异常");
        EVENT_ZH.put("LiveMsg.Parse.BatchSaved", "弹幕解析：批量保存成功");
        EVENT_ZH.put("LiveMsg.Parse.Progress", "弹幕解析：进度更新");
        EVENT_ZH.put("LiveMsg.Parse.DisableSecureFailed", "弹幕解析：禁用 XML 安全检查失败(可能影响大文件解析)");
        EVENT_ZH.put("LiveMsg.Parse.Saved", "弹幕解析：保存成功");
        EVENT_ZH.put("LiveMsg.Parse.Failed", "弹幕解析：失败");
        EVENT_ZH.put("LiveMsg.Parse.CloseFailed", "弹幕解析：关闭文件流失败");
        EVENT_ZH.put("GiftCatalog.Sync.Skip", "礼物价格目录同步：跳过或接口返回异常");
        EVENT_ZH.put("GiftCatalog.Sync.Done", "礼物价格目录同步：成功");
        EVENT_ZH.put("GiftCatalog.Sync.Failed", "礼物价格目录同步：请求失败");

        // 验证码
        EVENT_ZH.put("Captcha.WaitInterrupted", "等待验证码被中断");

        // 房间配置导入
        EVENT_ZH.put("RoomConfig.Import.Users.Success", "导入用户配置成功");
        EVENT_ZH.put("RoomConfig.Import.Rooms.Success", "导入房间配置成功");
        EVENT_ZH.put("RoomConfig.Import.Histories.Success", "导入录制历史配置成功");
        EVENT_ZH.put("RoomConfig.Import.Parts.Success", "导入分P配置成功");
        EVENT_ZH.put("RoomConfig.Import.Done", "导入配置完成");

        // 上传线路测速
        EVENT_ZH.put("UploadLine.TestAll.Failed", "上传线路测速：批量测试失败");
        EVENT_ZH.put("UploadLine.TestSpeed.Failed", "上传线路测速：单线路测试失败");

        // 推送通知
        EVENT_ZH.put("Notify.WxPusher.Send.Failed", "通知发送：WxPusher发送失败");
        EVENT_ZH.put("Notify.ServerChan3.Send.Failed", "通知发送：Server酱3发送失败");

        // 高能剪辑
        EVENT_ZH.put("HighEnergyCut.Segment.Generated", "高能剪辑：生成分片成功");
        EVENT_ZH.put("HighEnergyCut.Segment.Empty", "高能剪辑：未生成任何分片");
        EVENT_ZH.put("HighEnergyCut.Output.Generated", "高能剪辑：生成最终视频成功");
        EVENT_ZH.put("HighEnergyCut.Process.Failed", "高能剪辑：处理失败");
        EVENT_ZH.put("HighEnergyCut.Publish.WebPublish.Response", "高能剪辑：投稿响应(调试)");
        EVENT_ZH.put("HighEnergyCut.Publish.Captcha.Submit", "高能剪辑：提交验证码结果");
        EVENT_ZH.put("HighEnergyCut.Publish.Captcha.PublishResponse", "高能剪辑：验证码投稿响应(调试)");
        EVENT_ZH.put("HighEnergyCut.Publish.Captcha.VerifyFailedPause", "高能剪辑：验证码验证失败，暂停后重试");
        EVENT_ZH.put("HighEnergyCut.Publish.Captcha.HandleError", "高能剪辑：验证码处理流程异常");
        EVENT_ZH.put("HighEnergyCut.Publish.TimestampJump.GiveUp", "高能剪辑：时间戳跳变，放弃该投稿");
        EVENT_ZH.put("HighEnergyCut.Publish.MissingIds", "高能剪辑：投稿响应缺少 bvid/aid");
        EVENT_ZH.put("HighEnergyCut.Publish.Success", "高能剪辑：投稿成功");
        EVENT_ZH.put("HighEnergyCut.Upload.PreUpload.Failed", "高能剪辑：预上传失败");
        EVENT_ZH.put("HighEnergyCut.Upload.RateLimitWaitInterrupted", "高能剪辑：限流等待被中断");
        EVENT_ZH.put("HighEnergyCut.Upload.PreUpload.Success", "高能剪辑：预上传成功");
        EVENT_ZH.put("HighEnergyCut.Upload.Chunk.FileMissing", "高能剪辑：分片上传时文件不存在");
        EVENT_ZH.put("HighEnergyCut.Upload.Chunk.Progress", "高能剪辑：分片上传进度(调试)");
        EVENT_ZH.put("HighEnergyCut.Upload.Chunk.Error", "高能剪辑：分片上传失败，将等待后重试");
        EVENT_ZH.put("HighEnergyCut.Upload.Chunk.RetryWaitInterrupted", "高能剪辑：分片重试等待被中断");
        EVENT_ZH.put("HighEnergyCut.Upload.Complete.Retry", "高能剪辑：通知合并失败，重试中");
        EVENT_ZH.put("HighEnergyCut.Upload.Complete.Success", "高能剪辑：上传合并完成");

        // 视频预览
        EVENT_ZH.put("PartPreview.FFmpeg.NotFound", "视频预览：系统缺少 ffmpeg，生成可拖动 MP4 预览等部分功能可能无法正常使用");
        EVENT_ZH.put("PartPreview.FFmpeg.Resolved", "视频预览：已找到 ffmpeg");
        EVENT_ZH.put("PartPreview.PrepareFailed", "视频预览：生成可拖动 MP4 预览失败");
        EVENT_ZH.put("PartPreview.CacheCleanupFailed", "视频预览：清理预览缓存失败");

        // 登录/上传
        EVENT_ZH.put("Login.WebQr.PollFailed", "网页登录：轮询二维码登录状态失败");
        EVENT_ZH.put("Upload.RateLimit.WaitInterrupted", "上传：限流等待被中断");
        EVENT_ZH.put("Upload.Captcha.TimeoutSleepInterrupted", "上传：验证码超时后等待被中断");
        EVENT_ZH.put("Upload.Chunk.RetryWaitInterrupted", "上传：分片重试等待被中断");
        EVENT_ZH.put("Upload.Chunk.ThreadFailed", "上传：分片线程执行异常");
        EVENT_ZH.put("HighEnergyCut.Upload.Chunk.ThreadFailed", "高能剪辑：分片线程执行异常");

        // ByteUtils
        EVENT_ZH.put("ByteUtils.HexParseFailed", "ByteUtils：Hex解析失败");
        EVENT_ZH.put("ByteUtils.HexToString.DecodeFailed", "ByteUtils：Hex转字符串失败");
        EVENT_ZH.put("ByteUtils.ZlibInflateString.Failed", "ByteUtils：Zlib解压为字符串失败");
        EVENT_ZH.put("ByteUtils.ZlibInflateBytes.Failed", "ByteUtils：Zlib解压为字节失败");

        // blrec Webhook
        EVENT_ZH.put("BlrecWebhook.InvalidPayload", "blrec Webhook: 请求体无效");
        EVENT_ZH.put("BlrecWebhook.DispatchError", "blrec Webhook: 事件分发失败(找不到对应Service)");
        EVENT_ZH.put("Blrec.Room.AutoCreate", "blrec 事件: 房间不存在，自动创建");
        EVENT_ZH.put("Blrec.RecordingStarted.Success", "blrec 事件: 录制开始，已创建新的录制历史");
        EVENT_ZH.put("Blrec.RecordingFinished.RoomNotFound", "blrec 事件: 录制结束-未找到房间");
        EVENT_ZH.put("Blrec.RecordingFinished.HistoryNotFound", "blrec 事件: 录制结束-未找到录制历史");
        EVENT_ZH.put("Blrec.RecordingFinished.Success", "blrec 事件: 录制结束，已更新状态");
        EVENT_ZH.put("Blrec.RecordingCancelled.RoomNotFound", "blrec 事件: 录制取消-未找到房间");
        EVENT_ZH.put("Blrec.RecordingCancelled.HistoryNotFound", "blrec 事件: 录制取消-未找到录制历史");
        EVENT_ZH.put("Blrec.RecordingCancelled.Success", "blrec 事件: 录制取消，已更新状态");
        EVENT_ZH.put("Blrec.VideoFileCompleted.Skip", "blrec 事件: 视频文件完成-跳过(房间不存在或未在录制)");
        EVENT_ZH.put("Blrec.VideoFileCompleted.HistoryNotFound", "blrec 事件: 视频文件完成-未找到录制历史");
        EVENT_ZH.put("Blrec.VideoFileCompleted.PartExists", "blrec 事件: 视频文件完成-分P已存在，跳过");
        EVENT_ZH.put("Blrec.VideoFileCompleted.PartSaved", "blrec 事件: 视频文件完成，已保存分P记录");
        EVENT_ZH.put("Blrec.CoverDownloaded.Skip", "blrec 事件: 封面下载-跳过(房间不存在或未在录制)");
        EVENT_ZH.put("Blrec.CoverDownloaded.HistoryNotFound", "blrec 事件: 封面下载-未找到录制历史");
        EVENT_ZH.put("Blrec.CoverDownloaded.Success", "blrec 事件: 封面下载成功，已更新封面路径");
        EVENT_ZH.put("Blrec.RoomChange.RoomNotFound", "blrec 事件: 房间信息变更-未找到房间");
        EVENT_ZH.put("Blrec.RoomChange.Success", "blrec 事件: 房间信息变更成功");
        EVENT_ZH.put("Blrec.DanmakuCompleted.Skip", "blrec 事件: 弹幕文件完成-跳过(房间不存在或未在录制)");
        EVENT_ZH.put("Blrec.DanmakuCompleted.PartNotFound", "blrec 事件: 弹幕文件完成-未找到对应的视频分P");
        EVENT_ZH.put("Blrec.DanmakuCompleted.Processed", "blrec 事件: 弹幕文件完成，已触发解析");
        EVENT_ZH.put("Blrec.DanmakuCompleted.LiveMsgSkip", "blrec 事件: 弹幕文件完成-跳过弹幕解析");

        // 直播事件解析/统计缓存
        EVENT_ZH.put("RoomLiveEvent.Parse.Saved", "直播事件解析：已保存统计缓存");
        EVENT_ZH.put("RoomLiveEvent.Parse.Failed", "直播事件解析：解析失败");
        EVENT_ZH.put("RoomLiveEvent.Parse.SkipActive", "直播事件解析：跳过仍在写入的分P");
        EVENT_ZH.put("RoomLiveEvent.Parse.SkipFailedCached", "直播事件解析：跳过已确认失败且未变化的 XML");
        EVENT_ZH.put("RoomLiveEvent.Parse.DisableSecureFailed", "直播事件解析：关闭 XML 安全处理失败");
        EVENT_ZH.put("RoomLiveEvent.Backfill.Done", "直播事件解析：历史统计回填完成");
        
        // 系统配置
        EVENT_ZH.put("RoomConfig.Import.SystemConfigs.Success", "导入系统配置成功");
        EVENT_ZH.put("RoomConfig.Import.LiveMsgs.Success", "导入弹幕数据成功");
        EVENT_ZH.put("SystemConfig.Init", "系统配置：初始化");
        EVENT_ZH.put("SystemConfig.CreateDefault", "系统配置：创建默认配置");
        EVENT_ZH.put("SystemConfig.Updated", "系统配置：配置已更新");
        EVENT_ZH.put("SystemConfig.ApplyFailed", "系统配置：应用配置失败");

        // 限流器
        EVENT_ZH.put("RateLimiter.Init", "全局限流器：初始化");
        EVENT_ZH.put("RateLimiter.Update", "全局限流器：更新带宽限制");

        // 数据库备份
        EVENT_ZH.put("Database.Backup.Start", "数据库备份：开始备份 H2 数据库");
        EVENT_ZH.put("Database.Backup.Success", "数据库备份：备份创建成功");
        EVENT_ZH.put("Database.Backup.Failed", "数据库备份：备份失败");
        EVENT_ZH.put("Database.Backup.Cleanup.Success", "数据库备份：清理旧备份成功");
        EVENT_ZH.put("Database.Backup.Cleanup.Failed", "数据库备份：清理旧备份失败");
        EVENT_ZH.put("Database.Compact.Success", "数据库维护：压缩完成");
        EVENT_ZH.put("Database.Compact.Failed", "数据库维护：压缩失败");
        EVENT_ZH.put("Database.Compact.WebhookStillBusy", "数据库维护：仍在维护中，Webhook 暂存");
        EVENT_ZH.put("Database.Compact.WebhookSpooled", "数据库维护：Webhook 已暂存等待重放");
        EVENT_ZH.put("Database.Compact.WebhookSpoolFailed", "数据库维护：Webhook 暂存失败");
        EVENT_ZH.put("Database.Compact.WebhookReplayFailed", "数据库维护：暂存 Webhook 重放失败");

        // 接口/系统维护
        EVENT_ZH.put("BiliApi.WebCookie.BuvidAppendFailed", "B站 Web Cookie：补充 buvid 参数失败");
        EVENT_ZH.put("BiliApi.WebPublish.Request", "B站投稿接口：发起 Web 投稿请求");
        EVENT_ZH.put("Shutdown.Begin", "系统关闭：开始等待后台任务结束");
        EVENT_ZH.put("Shutdown.End", "系统关闭：后台任务等待结束");
        EVENT_ZH.put("SystemConfig.ApplyBoolean", "系统配置：应用布尔配置项");
        EVENT_ZH.put("UploadConnectionBudget.Init", "上传连接预算：初始化");
        EVENT_ZH.put("UploadConnectionBudget.Update", "上传连接预算：配置已更新");

        // 数据库维护
        EVENT_ZH.put("Database.Index.Cleanup.Start", "数据库索引清理：开始检查旧索引");
        EVENT_ZH.put("Database.Index.Cleanup.Success", "数据库索引清理：旧索引清理成功");
        EVENT_ZH.put("Database.Index.Cleanup.Failed", "数据库索引清理：旧索引清理失败");
        EVENT_ZH.put("Database.Schema.ColumnPatch.Start", "数据库结构修补：开始检查缺失字段");
        EVENT_ZH.put("Database.Schema.ColumnPatch.Success", "数据库结构修补：字段修补完成");
        EVENT_ZH.put("Database.Schema.ColumnPatch.Skip", "数据库结构修补：当前环境跳过字段修补");
        EVENT_ZH.put("Database.Schema.ColumnPatch.Failed", "数据库结构修补：字段修补失败");

        // 历史/审核/图片代理
        EVENT_ZH.put("History.Count.CalcFailed", "历史记录：统计数量计算失败");
        EVENT_ZH.put("History.AbnormalCount.QueryFailed", "历史记录：查询异常分P数量失败");
        EVENT_ZH.put("History.UploadFlowFallback.QueryFailed", "历史记录：查询上传流程回退数量失败");
        EVENT_ZH.put("History.Visibility.Switch.Failed", "历史记录：切换可见性失败");
        EVENT_ZH.put("History.Delete.LocalFileNotDeleted", "历史记录：本地文件未能删除");
        EVENT_ZH.put("History.CandidateFiles.SkipRootDir", "历史记录：候选文件扫描跳过根目录");
        EVENT_ZH.put("History.CandidateFiles.SkipUnreadablePath", "历史记录：候选文件扫描跳过不可读路径");
        EVENT_ZH.put("History.CandidateFiles.ScanFailed", "历史记录：候选文件扫描失败");
        EVENT_ZH.put("Part.List2.ReviewInfo.FetchFailed", "分P列表：获取审核信息失败");
        EVENT_ZH.put("Part.ReviewInfo.AuditDetail.Unexpected", "分P审核信息：审核详情返回异常");
        EVENT_ZH.put("Part.ReviewInfo.AuditDetail.Empty", "分P审核信息：审核详情为空");
        EVENT_ZH.put("Part.ReviewInfo.AuditDetail.LoadFailed", "分P审核信息：加载审核详情失败");
        EVENT_ZH.put("Part.ReviewInfo.Vupre.Unexpected", "分P审核信息：vupre 接口返回异常");
        EVENT_ZH.put("Part.ReviewInfo.LoadFailed", "分P审核信息：加载失败");
        EVENT_ZH.put("ImageProxy.Busy", "图片代理：当前并发繁忙，拒绝本次请求");
        EVENT_ZH.put("ImageProxy.EmptyBody", "图片代理：上游返回空内容");
        EVENT_ZH.put("ImageProxy.NonImageContentType", "图片代理：上游返回的内容不是图片");
        EVENT_ZH.put("ImageProxy.UpstreamNon2xx", "图片代理：上游返回非成功状态码");

        // blrec/录制事件
        EVENT_ZH.put("Blrec.RecordingStarted.ReuseHistory", "blrec 事件：录制开始，复用最近的录制历史");
        EVENT_ZH.put("Blrec.RecordStarted.ParseMergeIntervalFailed", "blrec 事件：解析合并间隔配置失败");
        EVENT_ZH.put("RecordStarted.ParseMergeIntervalFailed", "录制开始：解析合并间隔配置失败");
        EVENT_ZH.put("RoomStatusSyncJob.GetMasterInfoFailed", "直播状态同步：获取主播信息失败");

        // 统计缓存/历史刷新
        EVENT_ZH.put("Stats.RefreshHistory.SkipMaintenance", "统计缓存：维护模式中，跳过历史刷新");
        EVENT_ZH.put("Stats.RefreshHistory.SkipBusy", "统计缓存：刷新任务繁忙，跳过本次历史刷新");
        EVENT_ZH.put("Stats.Backfill.Failed", "统计缓存：历史回填失败");
        EVENT_ZH.put("Stats.Rebuild.Failed", "统计缓存：重建失败");
        EVENT_ZH.put("Stats.Cleanup.Failed", "统计缓存：清理失败");

        // 投稿 WebPublish/合集流程
        EVENT_ZH.put("Publish.WebPublish.MissingData", "投稿：Web 投稿响应缺少 data 字段");
        EVENT_ZH.put("Publish.WebPublish.Parsed", "投稿：Web 投稿响应解析完成");
        EVENT_ZH.put("Publish.WebPublish.ParseFailed", "投稿：Web 投稿响应解析失败");
        EVENT_ZH.put("Publish.PartUpload.Interrupted", "投稿流程：等待分P上传时被中断");
        EVENT_ZH.put("Publish.Season.ResolveSectionId.Empty", "投稿合集：没有可用的合集分区");
        EVENT_ZH.put("Publish.Season.ResolveSectionId.Failed", "投稿合集：解析合集分区失败");

        // 投稿补偿任务
        EVENT_ZH.put("PublishJob.PartCompensate.CleanOrphanedParts", "分P补偿：开始清理孤立分P");
        EVENT_ZH.put("PublishJob.PartCompensate.CleanOrphanedPartsFailed", "分P补偿：清理孤立分P失败");
        EVENT_ZH.put("PublishJob.PartCompensate.OrphanedPartMarked", "分P补偿：孤立分P已标记");
        EVENT_ZH.put("PublishJob.PartCompensate.InitialBackoff", "分P补偿：首次发现异常，等待下一轮确认");
        EVENT_ZH.put("PublishJob.PartCompensate.CompensateExhausted", "分P补偿：补偿次数已耗尽，放弃继续尝试");

        EVENT_ZH.put("PublishJob.PartCompensate.EditPublishedPart", "分P补偿：已投稿稿件转入编辑补分P流程");
        EVENT_ZH.put("PublishJob.PartCompensate.EditPublishedTriggered", "分P补偿：已触发已投稿稿件编辑流程");
        EVENT_ZH.put("PublishJob.PartCompensate.EditPublishedTriggerFailed", "分P补偿：触发已投稿稿件编辑流程失败");

        // 普通上传 multipart 流程
        EVENT_ZH.put("Upload.Multipart.ConfigReadFailed", "分片上传：读取 multipart 配置失败");
        EVENT_ZH.put("Upload.Multipart.FlowDecision", "分片上传：已决定使用的上传流程");
        EVENT_ZH.put("Upload.Multipart.SessionLocked", "分片上传：复用已锁定的上传会话");
        EVENT_ZH.put("Upload.Multipart.ResumeSession", "分片上传：复用已保存的断点会话");
        EVENT_ZH.put("Upload.Multipart.SessionReuseMiss", "分片上传：未找到可复用的断点会话");
        EVENT_ZH.put("Upload.Multipart.SessionReuseHit", "分片上传：找到可复用的断点会话");
        EVENT_ZH.put("Upload.Multipart.SessionCreated", "分片上传：已创建新的断点会话");
        EVENT_ZH.put("Upload.Multipart.SessionPaused", "分片上传：会话已在分片边界暂停");
        EVENT_ZH.put("Upload.Multipart.SessionRetryWait", "分片上传：会话已进入等待重试");
        EVENT_ZH.put("Upload.Multipart.SessionExpired", "分片上传：断点会话已失效，将重新开始该分P");
        EVENT_ZH.put("Upload.Multipart.Part.SkipDone", "分片上传：跳过已完成的分片");
        EVENT_ZH.put("Upload.Multipart.InitFallback", "分片上传：初始化失败，尝试回退方案");
        EVENT_ZH.put("Upload.Multipart.PrepareFallback", "分片上传：准备阶段异常，尝试回退方案");
        EVENT_ZH.put("Upload.Multipart.Part.DiagnosticUploadIdMismatch", "分片上传：诊断到 uploadId 不一致");
        EVENT_ZH.put("Upload.MultipartComplete.PayloadSummary", "分片上传完成：提交 payload 摘要");
        EVENT_ZH.put("Upload.MultipartComplete.ValidationFailed", "分片上传完成：提交前校验失败");
        EVENT_ZH.put("Upload.MultipartComplete.FatalError", "分片上传完成：发生致命错误");
        EVENT_ZH.put("Upload.MultipartComplete.ConflictRetry", "分片上传完成：检测到冲突，准备重试");
        EVENT_ZH.put("Upload.MultipartComplete.Retry", "分片上传完成：提交失败，正在重试");
        EVENT_ZH.put("Upload.MultipartComplete.WillRetry", "分片上传完成：本次失败，将安排后续重试");
        EVENT_ZH.put("Upload.Part.RetryEnqueueAttempt", "上传重试：尝试重新加入上传队列");
        EVENT_ZH.put("Upload.Part.RetryEnqueueError", "上传重试：重新加入上传队列失败");
        EVENT_ZH.put("Upload.Resume.RetryScheduled", "恢复上传：已安排延迟重试入队");
        EVENT_ZH.put("Upload.Resume.RetryAttempt", "恢复上传：正在重试加入上传队列");
        EVENT_ZH.put("Upload.Resume.RetryStop", "恢复上传：停止延迟重试");
        EVENT_ZH.put("Upload.Resume.RetryFailed", "恢复上传：延迟重试执行异常");

        // multipart 调试日志
        EVENT_ZH.put("Upload.MultipartDebug.Complete.Request", "分片上传调试：complete 请求");
        EVENT_ZH.put("Upload.MultipartDebug.Complete.Response", "分片上传调试：complete 响应");
        EVENT_ZH.put("Upload.MultipartDebug.InitNew.Request", "分片上传调试：新版初始化请求");
        EVENT_ZH.put("Upload.MultipartDebug.InitNew.Response", "分片上传调试：新版初始化响应");
        EVENT_ZH.put("Upload.MultipartDebug.InitLegacy.Request", "分片上传调试：旧版初始化请求");
        EVENT_ZH.put("Upload.MultipartDebug.InitLegacy.Response", "分片上传调试：旧版初始化响应");
        EVENT_ZH.put("Upload.MultipartDebug.Part.Request", "分片上传调试：分片请求");
        EVENT_ZH.put("Upload.MultipartDebug.Part.Response", "分片上传调试：分片响应");
        EVENT_ZH.put("Upload.MultipartDebug.Part.SelectedReq", "分片上传调试：已选择的分片请求参数");
        EVENT_ZH.put("Upload.MultipartDebug.Part.FirstValidNotFirst", "分片上传调试：首个有效请求不是第一组候选");
        EVENT_ZH.put("Upload.MultipartDebug.SignedPut.Response", "分片上传调试：签名 PUT 响应");

        // 高能剪辑投稿/上传
        EVENT_ZH.put("HighEnergyCut.Publish.WebPublish.MissingData", "高能剪辑：Web 投稿响应缺少 data 字段");
        EVENT_ZH.put("HighEnergyCut.Publish.WebPublish.Parsed", "高能剪辑：Web 投稿响应解析完成");
        EVENT_ZH.put("HighEnergyCut.Publish.WebPublish.ParseFailed", "高能剪辑：Web 投稿响应解析失败");
        EVENT_ZH.put("HighEnergyCut.Upload.Multipart.ConfigReadFailed", "高能剪辑：读取 multipart 配置失败");
        EVENT_ZH.put("HighEnergyCut.Upload.Multipart.SessionLocked", "高能剪辑：复用已锁定的上传会话");
        EVENT_ZH.put("HighEnergyCut.Upload.Multipart.InitFallback", "高能剪辑：分片初始化失败，尝试回退方案");
        EVENT_ZH.put("HighEnergyCut.Upload.Multipart.PrepareFallback", "高能剪辑：分片准备阶段异常，尝试回退方案");
        EVENT_ZH.put("HighEnergyCut.Upload.MultipartComplete.PayloadSummary", "高能剪辑：分片完成 payload 摘要");
        EVENT_ZH.put("HighEnergyCut.Upload.MultipartComplete.ValidationFailed", "高能剪辑：分片完成提交前校验失败");
        EVENT_ZH.put("HighEnergyCut.Upload.MultipartComplete.FatalError", "高能剪辑：分片完成发生致命错误");
        EVENT_ZH.put("HighEnergyCut.Upload.MultipartComplete.ConflictRetry", "高能剪辑：分片完成检测到冲突，准备重试");
        EVENT_ZH.put("HighEnergyCut.Upload.MultipartComplete.Retry", "高能剪辑：分片完成提交失败，正在重试");
    }
    static {
        // 强制归档硬停止/恢复
        EVENT_ZH.put("History.RestoreForceArchive.Success", "强制归档已恢复处理");
        EVENT_ZH.put("StreamEnd.SkipHistoryUpdate", "下播事件：目标稿件已强制归档或房间不匹配，跳过更新结束时间");
        EVENT_ZH.put("RecordEnd.SkipHistoryUpdate", "录制结束事件：目标稿件已强制归档或房间不匹配，跳过更新结束时间");
        EVENT_ZH.put("FileClosed.SkipHistoryUpdate", "文件关闭事件：目标稿件已强制归档或房间不匹配，跳过更新结束时间");
        EVENT_ZH.put("RoomStatusSyncJob.SkipForceArchivedHistory", "直播状态同步：当前稿件已强制归档，跳过历史结束时间更新");
        EVENT_ZH.put("Blrec.RecordingFinished.SkipForceArchived", "blrec 录制结束：当前稿件已强制归档，跳过历史结束时间更新");
        EVENT_ZH.put("Blrec.RecordingCancelled.SkipForceArchived", "blrec 录制取消：当前稿件已强制归档，跳过历史结束时间更新");

    }
    static {
        // 各类日志事件
        EVENT_ZH.put("BiliApi.Cookie.NormalizeFailed", "B站 Cookie：格式标准化失败，已继续使用原始 Cookie");
        EVENT_ZH.put("BiliApi.UserCards.Failed", "B站用户卡片：批量获取用户信息失败");
        EVENT_ZH.put("History.AbandonMsgQueue.Success", "历史记录：已放弃待发送的弹幕队列");
        EVENT_ZH.put("History.CandidateFiles.SkipUnresolvablePath", "历史记录：候选文件扫描跳过无法解析的路径");
        EVENT_ZH.put("Part.ArchiveProgress.FetchFailed", "分P进度：获取稿件处理进度失败");
        EVENT_ZH.put("PartPreview.CacheDeleteFailed", "分P预览：删除预览缓存失败");
        EVENT_ZH.put("Room.Avatar.BatchRefreshFailed", "房间头像：批量刷新失败");
        EVENT_ZH.put("SystemConfig.IgnoreObsolete", "系统配置：忽略已废弃的配置项");
        EVENT_ZH.put("SystemConfig.RemoveObsolete", "系统配置：移除已废弃的配置项");
        EVENT_ZH.put("User.Profile.BatchRefreshFailed", "用户资料：批量刷新失败");

        // 弹幕队列调度
        EVENT_ZH.put("DanmakuDispatch.Reply.Duplicate", "评论发送队列：稿件已在队列中，跳过重复入队");
        EVENT_ZH.put("DanmakuDispatch.Reply.Enqueued", "评论发送队列：稿件已入队");
        EVENT_ZH.put("DanmakuDispatch.Reply.Rejected", "评论发送队列：线程池拒绝任务，未能入队");
        EVENT_ZH.put("DanmakuDispatch.Normal.Duplicate", "普通弹幕发送队列：分P已在队列中，跳过重复入队");
        EVENT_ZH.put("DanmakuDispatch.Normal.Enqueued", "普通弹幕发送队列：分P已入队");
        EVENT_ZH.put("DanmakuDispatch.Normal.Rejected", "普通弹幕发送队列：线程池拒绝任务，稍后重试");
        EVENT_ZH.put("DanmakuDispatch.Normal.Sent", "普通弹幕发送队列：已发送一条弹幕并安排下一次发送");
        EVENT_ZH.put("DanmakuDispatch.Normal.Error", "普通弹幕发送队列：发送流程异常，稍后重试");
        EVENT_ZH.put("DanmakuDispatch.Normal.SkipArchivedOrLocked", "普通弹幕发送队列：稿件已归档或锁定，停止发送并标记队列");
        EVENT_ZH.put("DanmakuDispatch.High.Duplicate", "SC/上舰弹幕发送队列：分P已在队列中，跳过重复入队");
        EVENT_ZH.put("DanmakuDispatch.High.Enqueued", "SC/上舰弹幕发送队列：分P已入队");
        EVENT_ZH.put("DanmakuDispatch.High.Rejected", "SC/上舰弹幕发送队列：线程池拒绝任务，稍后重试");
        EVENT_ZH.put("DanmakuDispatch.High.Sent", "SC/上舰弹幕发送队列：已发送一条弹幕并安排下一次发送");
        EVENT_ZH.put("DanmakuDispatch.High.Error", "SC/上舰弹幕发送队列：发送流程异常，稍后重试");
        EVENT_ZH.put("DanmakuDispatch.High.SkipArchivedOrLocked", "SC/上舰弹幕发送队列：稿件已归档或锁定，停止发送并标记队列");
        EVENT_ZH.put("DanmakuDispatch.High.SleepInterrupted", "SC/上舰弹幕发送队列：等待过程被中断");

        // 弹幕发送同步
        EVENT_ZH.put("LiveMsgSendSync.Reconcile.Done", "弹幕发送同步：队列校准完成");
        EVENT_ZH.put("LiveMsgSendSync.Reply.Dispatch.Done", "评论发送同步：本轮评论发送完成");
        EVENT_ZH.put("LiveMsgSendSync.Reply.PageSnapshot", "视频评论发送：已获取线上分P页码快照");
        EVENT_ZH.put("LiveMsgSendSync.Reply.PageSnapshot.Failed", "视频评论发送：获取线上分P页码快照失败，改用本地分P顺序兜底");
        EVENT_ZH.put("LiveMsgSendSync.Visibility.High.SwitchPublic.RateLimit", "SC/上舰弹幕：切公开时触发限流，已暂停账号并稍后重试");
        EVENT_ZH.put("LiveMsgSendSync.Visibility.High.SwitchPublic.Skip", "SC/上舰弹幕：切公开失败，跳过本次发送并稍后重试");
        EVENT_ZH.put("LiveMsgSendSync.Visibility.High.SwitchPrivate.Deferred", "SC/上舰弹幕：切回仅自己可见触发限流，已延后处理");

        // 投稿状态同步/锁定检测
        EVENT_ZH.put("VideoSync.ManualRefresh.VideoInfoFailed", "稿件状态刷新：获取公开视频信息失败");
        EVENT_ZH.put("VideoSync.ManualRefresh.PartInfoFailed", "稿件状态刷新：获取投稿后台分P信息失败");
        EVENT_ZH.put("VideoSync.LockedDetect.Failed", "稿件状态同步：检测稿件锁定状态失败");
        EVENT_ZH.put("VideoSync.LockedAuditReason.Failed", "稿件状态同步：获取稿件锁定原因失败");
        EVENT_ZH.put("VideoSync.LockedArchive.AutoForceArchived", "稿件状态同步：检测到稿件已锁定，已自动强制归档并停止后续任务");
        EVENT_ZH.put("VideoSync.PartOrder.Anomaly", "稿件状态同步：线上分P顺序异常，已按保守策略处理");

        // 投稿编辑/上传
        EVENT_ZH.put("Publish.EditParts.FileNotUnderWorkPath", "分P编辑：文件不在工作目录下，跳过该文件");
        EVENT_ZH.put("Publish.EditParts.StalePartDeleteFailed", "分P编辑：删除过期分P记录失败");
        EVENT_ZH.put("Upload.Part.Paused", "分P上传：上传任务已暂停");
        EVENT_ZH.put("Upload.SerialScheduler.SkipPaused", "分P上传串行调度：上传已暂停，跳过本次任务");
        EVENT_ZH.put("StorageRoot.HealthChanged", "存储目录：在线状态发生变化");
        EVENT_ZH.put("StorageRoot.WorkPathChange.Applied", "工作目录变更：已按用户选择应用");
        EVENT_ZH.put("StorageRoot.WorkPathChange.Failed", "工作目录变更：验证或应用失败");
        EVENT_ZH.put("StorageLifecycle.Migration.Done", "本地素材位置迁移：旧数据迁移完成");
        EVENT_ZH.put("StorageLifecycle.Migration.Failed", "本地素材位置迁移：旧数据迁移失败");
        EVENT_ZH.put("PartFile.Operation.Pending", "本地素材处理：等待存储目录恢复或确认");
        EVENT_ZH.put("PartFile.Operation.Succeeded", "本地素材处理：操作完成");
        EVENT_ZH.put("PartFile.Operation.Failed", "本地素材处理：操作失败");
        EVENT_ZH.put("PartFile.Operation.RecoveryFailed", "本地素材处理：中断恢复失败");
        EVENT_ZH.put("PublishJob.PartCompensate.SkipLocalFileState", "分P补偿上传：按本地素材状态跳过");
        EVENT_ZH.put("Upload.Part.LocalFileUnavailable", "分P上传：本地素材当前不可读取");
        EVENT_ZH.put("Publish.Edit.PartUpload.LocalFileUnavailable", "分P编辑上传：本地素材当前不可读取");
    }

    private LogKvs() {
    }

    public static LogKvs event(String eventName) {
        return new LogKvs().add("event", eventName);
    }

    public LogKvs add(String key, Object value) {
        if (key == null || key.isBlank()) {
            return this;
        }

        finalized = false;
        if (Objects.equals(key, "event")) {
            this.eventName = value == null ? null : String.valueOf(value);
        }
        if (Objects.equals(key, "msg") || Objects.equals(key, "hint")) {
            this.msgPresent = true;
        }

        if (sb.length() > 0) {
            sb.append(" | ");
        }
        sb.append(key).append('=');
        sb.append(sanitize(value));
        return this;
    }

    /**
     * 中文解释给用户看的简短说明。
     */
    public LogKvs msg(String zh) {
        return add("msg", zh);
    }

    /**
     * 可选提示：给排查问题用的补充建议。
     */
    public LogKvs hint(String zh) {
        return add("hint", zh);
    }

    /**
     * URL 日志字段：默认去掉 query/fragment，避免泄露 token。
     */
    public LogKvs addUrl(String key, Object url) {
        if (url == null) {
            return add(key, null);
        }
        String raw = String.valueOf(url);
        String safe = raw;
        try {
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            String path = uri.getRawPath();
            if (scheme != null && host != null) {
                StringBuilder b = new StringBuilder();
                b.append(scheme).append("://").append(host);
                if (port != -1) {
                    b.append(':').append(port);
                }
                if (path != null) {
                    b.append(path);
                }
                safe = b.toString();
            } else {
                int q = raw.indexOf('?');
                if (q >= 0) {
                    safe = raw.substring(0, q);
                }
            }
        } catch (Exception ignore) {
            int q = raw.indexOf('?');
            if (q >= 0) {
                safe = raw.substring(0, q);
            }
        }
        return add(key, safe);
    }

    public LogKvs addIfNotBlank(String key, String value) {
        if (value == null || value.isBlank()) {
            return this;
        }
        return add(key, value);
    }

    /**
     * 统一耗时写入（毫秒），startNs 应来自 System.nanoTime()。
     */
    public LogKvs addCostMs(String key, long startNs) {
        if (startNs <= 0L) {
            return this;
        }
        return add(key, (System.nanoTime() - startNs) / 1_000_000L);
    }

    /**
     * 统一阶段耗时前缀：stage.{name}.costMs。
     */
    public LogKvs addStageCostMs(String stageName, long startNs) {
        if (stageName == null || stageName.isBlank()) {
            return this;
        }
        return addCostMs("stage." + normalizeKeySegment(stageName) + ".costMs", startNs);
    }

    /**
     * 统一阶段字段前缀：stage.{name}.{metric}。
     */
    public LogKvs addStageField(String stageName, String metric, Object value) {
        if (stageName == null || stageName.isBlank() || metric == null || metric.isBlank()) {
            return this;
        }
        return add("stage." + normalizeKeySegment(stageName) + "." + normalizeKeySegment(metric), value);
    }

    /**
     * 统一轮次计数字段：round.{name}Count。
     */
    public LogKvs addRoundCount(String name, int value) {
        if (name == null || name.isBlank()) {
            return this;
        }
        return add("round." + normalizeKeySegment(name) + "Count", value);
    }

    /**
     * 统一轮次计数字段：round.{name}Count。
     */
    public LogKvs addRoundCount(String name, long value) {
        if (name == null || name.isBlank()) {
            return this;
        }
        return add("round." + normalizeKeySegment(name) + "Count", value);
    }

    private static String normalizeKeySegment(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String s = raw.trim();
        s = s.replaceAll("[^A-Za-z0-9._-]", "_");
        s = s.replace("..", ".");
        return s;
    }

    private static String sanitize(Object value) {
        if (value == null) {
            return "null";
        }
        String s = String.valueOf(value);
        // 替换控制字符和分隔符，防止日志注入和格式破坏
        s = s.replace("\r", "\\r").replace("\n", "\\n");
        s = s.replace("|", "/");
        return s;
    }

    @Override
    public String toString() {
        if (!finalized) {
            // 如果没有显式 msg/hint，则按 event 自动补充中文解释
            if (!msgPresent && eventName != null) {
                String zh = EVENT_ZH.get(eventName);
                if (zh != null && !zh.isBlank()) {
                    add("msg", zh);
                } else {
                    // 兜底：至少保证有中文提示，避免用户看不懂
                    add("msg", "事件:" + eventName);
                }
            }
            finalized = true;
        }
        return sb.toString();
    }
}
