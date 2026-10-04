package top.sshh.bililiverecoder.config;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import top.sshh.bililiverecoder.entity.NotificationChannel;
import top.sshh.bililiverecoder.entity.DiagnosticExportRequest;
import top.sshh.bililiverecoder.entity.NotificationDelivery;
import top.sshh.bililiverecoder.entity.NotificationRule;
import top.sshh.bililiverecoder.entity.PartFileLocation;
import top.sshh.bililiverecoder.entity.PublishTask;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.PublishTaskOperation;
import top.sshh.bililiverecoder.entity.PublishTaskSource;
import top.sshh.bililiverecoder.entity.PublishTaskState;
import top.sshh.bililiverecoder.entity.PublishTaskStatusDto;
import top.sshh.bililiverecoder.entity.RecordWebhookInboxStatusDto;
import top.sshh.bililiverecoder.entity.VideoCommentTaskStatusDto;
import top.sshh.bililiverecoder.entity.VideoVisibilityRestoreStatusDto;
import top.sshh.bililiverecoder.entity.HistoryPostPublishStatusDto;
import top.sshh.bililiverecoder.entity.HistoryDeletionTask;
import top.sshh.bililiverecoder.entity.RoomLiveSessionStats;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.controller.RoomController;
import top.sshh.bililiverecoder.notification.NotificationEvent;
import top.sshh.bililiverecoder.notification.NotificationEventDescriptor;
import top.sshh.bililiverecoder.notification.NotificationMessage;
import top.sshh.bililiverecoder.notification.NotificationSendResult;
import top.sshh.bililiverecoder.service.RoomDeletionService;
import top.sshh.bililiverecoder.service.StorageRootChangeAssessmentService;
import top.sshh.bililiverecoder.service.CaptchaService;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliupRuntimeHintsRegistrarTest {

    @Test
    void registersMaintenanceConnectionProxyAndPoolRestartReflection() {
        RuntimeHints hints = new RuntimeHints();
        new BiliupRuntimeHintsRegistrar().registerHints(hints, getClass().getClassLoader());
        assertTrue(RuntimeHintsPredicates.proxies().forInterfaces(java.sql.Connection.class).test(hints));
        assertTrue(RuntimeHintsPredicates.reflection().onType(java.sql.Connection.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS).test(hints));
        assertTrue(RuntimeHintsPredicates.reflection().onType(com.zaxxer.hikari.HikariConfig.class)
                .withMemberCategory(MemberCategory.DECLARED_FIELDS).test(hints));
    }

    @Test
    void registersNotificationTypesForReflection() {
        RuntimeHints hints = new RuntimeHints();

        new BiliupRuntimeHintsRegistrar().registerHints(hints, getClass().getClassLoader());

        assertReflectionRegistered(hints, NotificationChannel.class);
        assertReflectionRegistered(hints, DiagnosticExportRequest.class);
        assertReflectionRegistered(hints, NotificationRule.class);
        assertReflectionRegistered(hints, NotificationDelivery.class);
        assertReflectionRegistered(hints, RoomLiveSessionStats.class);
        assertReflectionRegistered(hints, StorageRoot.class);
        assertReflectionRegistered(hints, PartFileLocation.class);
        assertReflectionRegistered(hints, NotificationEvent.class);
        assertReflectionRegistered(hints, NotificationEventDescriptor.class);
        assertReflectionRegistered(hints, NotificationMessage.class);
        assertReflectionRegistered(hints, NotificationSendResult.class);
        assertReflectionRegistered(hints, RoomController.RoomDeletionRequest.class);
        assertReflectionRegistered(hints, RoomDeletionService.DeletionPreview.class);
        assertReflectionRegistered(hints, StorageRootChangeAssessmentService.Snapshot.class);
        assertReflectionRegistered(hints, StorageRootChangeAssessmentService.State.class);
        assertReflectionRegistered(hints, PublishTask.class);
        assertReflectionRegistered(hints, RecordHistory.class);
        assertReflectionRegistered(hints, top.sshh.bililiverecoder.entity.RecordHistoryPart.class);
        assertReflectionRegistered(hints, PublishTaskOperation.class);
        assertReflectionRegistered(hints, PublishTaskSource.class);
        assertReflectionRegistered(hints, PublishTaskState.class);
        assertReflectionRegistered(hints, PublishTaskStatusDto.class);
        assertReflectionRegistered(hints, RecordWebhookInboxStatusDto.class);
        assertReflectionRegistered(hints, VideoCommentTaskStatusDto.class);
        assertReflectionRegistered(hints, VideoVisibilityRestoreStatusDto.class);
        assertReflectionRegistered(hints, HistoryPostPublishStatusDto.class);
        assertReflectionRegistered(hints, HistoryDeletionTask.class);
        assertReflectionRegistered(hints, StorageRoot.RootType.class);
        assertReflectionRegistered(hints, CaptchaService.ChallengeStatus.class);
    }

    private void assertReflectionRegistered(RuntimeHints hints, Class<?> type) {
        assertTrue(RuntimeHintsPredicates.reflection()
                .onType(type)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
                .test(hints), type.getName() + " constructors should be registered");
        assertTrue(RuntimeHintsPredicates.reflection()
                .onType(type)
                .withMemberCategory(MemberCategory.DECLARED_FIELDS)
                .test(hints), type.getName() + " fields should be registered");
        assertTrue(RuntimeHintsPredicates.reflection()
                .onType(type)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                .test(hints), type.getName() + " public methods should be registered");
        assertTrue(RuntimeHintsPredicates.reflection()
                .onType(type)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_METHODS)
                .test(hints), type.getName() + " declared methods should be registered");
    }
}
