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
import top.sshh.bililiverecoder.entity.RoomLiveSessionStats;
import top.sshh.bililiverecoder.entity.StorageRoot;
import top.sshh.bililiverecoder.controller.RoomController;
import top.sshh.bililiverecoder.notification.NotificationEvent;
import top.sshh.bililiverecoder.notification.NotificationEventDescriptor;
import top.sshh.bililiverecoder.notification.NotificationMessage;
import top.sshh.bililiverecoder.notification.NotificationSendResult;
import top.sshh.bililiverecoder.service.RoomDeletionService;
import top.sshh.bililiverecoder.service.StorageRootChangeAssessmentService;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BiliupRuntimeHintsRegistrarTest {

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
