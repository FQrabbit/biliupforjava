package top.sshh.bililiverecoder.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;

public class BiliupRuntimeHintsRegistrar implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        String[] classesToRegister = {
            "top.sshh.bililiverecoder.entity.data.BiliLiveRoomInfoResponse",
            "top.sshh.bililiverecoder.entity.data.BiliLiveRoomInfoResponse$RoomInfo",
            "top.sshh.bililiverecoder.entity.data.BiliLiveMasterInfoResponse",
            "top.sshh.bililiverecoder.entity.data.BiliLiveMasterInfoResponse$MasterInfo",
            "top.sshh.bililiverecoder.entity.data.BiliLiveMasterInfoResponse$Info",
            "top.sshh.bililiverecoder.entity.data.BiliVideoInfoResponse",
            "top.sshh.bililiverecoder.entity.data.BiliVideoInfoResponse$BiliVideoInfo",
            "top.sshh.bililiverecoder.entity.data.BiliVideoInfoResponse$BiliVideoInfoPart",
            "top.sshh.bililiverecoder.entity.data.BiliVideoPartInfoResponse",
            "top.sshh.bililiverecoder.entity.data.BiliVideoPartInfoResponse$BiliVideoInfo",
            "top.sshh.bililiverecoder.entity.data.BiliVideoPartInfoResponse$Video",
            "top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse",
            "top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse$AuditData",
            "top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse$Appeal",
            "top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse$ProblemDetail",
            "top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse$PictureData",
            "top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse$RejectVideoExplain",
            "top.sshh.bililiverecoder.entity.data.BiliVideoAuditDetailResponse$RejectVideoLink",
            "top.sshh.bililiverecoder.entity.data.BiliDmResponse",
            "top.sshh.bililiverecoder.entity.data.BiliDmResponse$BiliDm",
            "top.sshh.bililiverecoder.entity.data.BiliReplyResponse",
            "top.sshh.bililiverecoder.entity.data.BiliReplyResponse$Reply",
            "top.sshh.bililiverecoder.entity.data.BiliReply",
            "top.sshh.bililiverecoder.entity.data.BiliWebLoginDto",
            "top.sshh.bililiverecoder.entity.data.BiliWebLoginDto$Data",
            "top.sshh.bililiverecoder.entity.data.BiliUserCardResponseDto",
            "top.sshh.bililiverecoder.entity.data.BiliSessionDto",
            "top.sshh.bililiverecoder.util.BiliApi$BiliResponseDto",
            "top.sshh.bililiverecoder.util.BiliApi$GenerateQRDto",
            "top.sshh.bililiverecoder.util.BiliApi$BiliUserCardResponseDto",
            "top.sshh.bililiverecoder.entity.data.BillBuvId",
            "top.sshh.bililiverecoder.entity.data.BiliUserCard",
            "top.sshh.bililiverecoder.entity.data.BiliUserCard$LevelInfo",
            "top.sshh.bililiverecoder.entity.data.VideoEditUploadDto",
            "top.sshh.bililiverecoder.entity.data.VideoEditUploadDto$DescDto",
            "top.sshh.bililiverecoder.entity.data.VideoUploadDto",
            "top.sshh.bililiverecoder.entity.data.InitRequestDto",
            "top.sshh.bililiverecoder.entity.data.KodoPart",
            "top.sshh.bililiverecoder.entity.data.SingleVideoDto",
            "top.sshh.bililiverecoder.entity.data.DescV2Dto",
            "top.sshh.bililiverecoder.entity.blrec.BlrecDataDTO",
            "top.sshh.bililiverecoder.entity.blrec.BlrecEventDTO",
            "top.sshh.bililiverecoder.entity.blrec.BlrecRoomInfoDTO",
            "top.sshh.bililiverecoder.entity.ExportConfigParams",
            "top.sshh.bililiverecoder.entity.LogAlert",
            "top.sshh.bililiverecoder.entity.RecordEventDTO",
            "top.sshh.bililiverecoder.entity.RecordEventData",
            "top.sshh.bililiverecoder.entity.BlrecRoomInfo",
            "top.sshh.bililiverecoder.entity.BlrecUserInfo",
            "top.sshh.bililiverecoder.entity.BlrecData",
            "top.sshh.bililiverecoder.entity.RecordRoom",
            "top.sshh.bililiverecoder.entity.BiliBiliUser",
            "top.sshh.bililiverecoder.entity.RecordHistory",
            "top.sshh.bililiverecoder.entity.RecordHistoryPart",
            "top.sshh.bililiverecoder.entity.SystemConfig",
            "top.sshh.bililiverecoder.entity.LiveMsg",
            "top.sshh.bililiverecoder.entity.HighEnergyCut",
            "top.sshh.bililiverecoder.entity.RecordHistoryDTO",
            "top.sshh.bililiverecoder.util.FastjsonWebhookDateDeserializer",
            "top.sshh.bililiverecoder.util.bili.user.Fans_medal",
            "top.sshh.bililiverecoder.util.bili.user.Label",
            "top.sshh.bililiverecoder.util.bili.user.Level_exp",
            "top.sshh.bililiverecoder.util.bili.user.Live_room",
            "top.sshh.bililiverecoder.util.bili.user.Nameplate",
            "top.sshh.bililiverecoder.util.bili.user.Official",
            "top.sshh.bililiverecoder.util.bili.user.Pendant",
            "top.sshh.bililiverecoder.util.bili.user.Profession",
            "top.sshh.bililiverecoder.util.bili.user.School",
            "top.sshh.bililiverecoder.util.bili.user.Series",
            "top.sshh.bililiverecoder.util.bili.user.Sys_notice",
            "top.sshh.bililiverecoder.util.bili.user.Theme",
            "top.sshh.bililiverecoder.util.bili.user.UserMyRootBean",
            "top.sshh.bililiverecoder.util.bili.user.UserMyRootBean$Data",
            "top.sshh.bililiverecoder.util.bili.user.UserRootBean",
            "top.sshh.bililiverecoder.util.bili.user.UserRootBean$Data",
            "top.sshh.bililiverecoder.util.bili.user.User_honour_info",
            "top.sshh.bililiverecoder.util.bili.user.Vip",
            "top.sshh.bililiverecoder.util.bili.user.Watched_show",
            "top.sshh.bililiverecoder.util.bili.Cookie",
            "top.sshh.bililiverecoder.util.bili.HttpClientResult",
            "top.sshh.bililiverecoder.util.bili.WebCookie",
            "top.sshh.bililiverecoder.util.BiliApi$BiliResponseDto",
            "top.sshh.bililiverecoder.util.bili.upload.pojo.ChunkUploadBean",
            "top.sshh.bililiverecoder.util.bili.upload.pojo.CompleteUploadBean",
            "top.sshh.bililiverecoder.util.bili.upload.pojo.EditorPreUploadBean",
            "top.sshh.bililiverecoder.util.bili.upload.pojo.EditorSpaceBean",
            "top.sshh.bililiverecoder.util.bili.upload.pojo.LineUploadBean",
            "top.sshh.bililiverecoder.util.bili.upload.pojo.PreUploadBean",
            "top.sshh.bililiverecoder.util.bili.upload.pojo.PublishVideoBean",
            "top.sshh.bililiverecoder.util.bili.upload.pojo.PublishVideoBean$Data",
            "top.sshh.bililiverecoder.util.bili.upload.pojo.EditorPreUploadBean$Data",
            "top.sshh.bililiverecoder.util.bili.upload.pojo.EditorSpaceBean$Data",
            "com.zjiecode.wxpusher.client.bean.Message",
            "com.zjiecode.wxpusher.client.bean.Result",
            "com.zjiecode.wxpusher.client.bean.MessageResult"
        };

        for (String className : classesToRegister) {
            hints.reflection().registerType(TypeReference.of(className), 
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, 
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS, 
                MemberCategory.INVOKE_DECLARED_METHODS,
                MemberCategory.DECLARED_FIELDS);
        }

        // 注册 WebSocket 反射发送消息相关的类
        hints.reflection().registerType(TypeReference.of("jakarta.websocket.Session"), 
            MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_DECLARED_METHODS);
        hints.reflection().registerType(TypeReference.of("jakarta.websocket.RemoteEndpoint$Async"), 
            MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_DECLARED_METHODS);
        String[] log4jMessageFactories = {
            "org.apache.logging.log4j.message.DefaultFlowMessageFactory",
            "org.apache.logging.log4j.message.ParameterizedMessageFactory",
            "org.apache.logging.log4j.message.ParameterizedNoReferenceMessageFactory",
            "org.apache.logging.log4j.message.ReusableMessageFactory",
            "org.apache.logging.log4j.message.SimpleMessageFactory",
            "org.apache.logging.log4j.message.FormattedMessageFactory",
            "org.apache.logging.log4j.message.StringFormatterMessageFactory",
            "org.apache.logging.log4j.message.MessageFormatMessageFactory",
            "org.apache.logging.log4j.message.LocalizedMessageFactory"
        };
        for (String className : log4jMessageFactories) {
            hints.reflection().registerType(TypeReference.of(className),
                MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        }
        hints.reflection().registerType(TypeReference.of("org.springframework.web.socket.adapter.standard.StandardWebSocketSession"), 
            MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_DECLARED_METHODS);

        // 注册 undertow websocket
        hints.reflection().registerType(TypeReference.of("io.undertow.websockets.jsr.ServerWebSocketContainer"), 
            MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, 
            MemberCategory.INVOKE_DECLARED_METHODS);
    }
}
