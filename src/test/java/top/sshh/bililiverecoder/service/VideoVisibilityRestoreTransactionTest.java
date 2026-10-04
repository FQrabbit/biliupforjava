package top.sshh.bililiverecoder.service;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.entity.RecordHistory;
import top.sshh.bililiverecoder.entity.VideoVisibilityRestoreTask;
import top.sshh.bililiverecoder.repo.BiliUserRepository;
import top.sshh.bililiverecoder.repo.RecordHistoryRepository;
import top.sshh.bililiverecoder.repo.VideoVisibilityRestoreTaskRepository;
import top.sshh.bililiverecoder.util.HttpClientUtil;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest(properties = {
        "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:visibility-restore-transactions;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(VideoVisibilityRestoreService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VideoVisibilityRestoreTransactionTest {
    @Autowired
    private VideoVisibilityRestoreService service;
    @Autowired
    private VideoVisibilityRestoreTaskRepository tasks;
    @Autowired
    private RecordHistoryRepository histories;
    @Autowired
    private BiliUserRepository users;

    private OkHttpClient originalClient;
    private Long restoringTaskId;
    private String platformResponse;
    private final AtomicInteger requestCount = new AtomicInteger();

    @BeforeEach
    void setUp() {
        tasks.deleteAllInBatch();
        histories.deleteAll();
        users.deleteAll();
        platformResponse = "{\"code\":0,\"message\":\"OK\"}";
        requestCount.set(0);
        originalClient = (OkHttpClient) ReflectionTestUtils.getField(HttpClientUtil.class, "client");
        OkHttpClient testClient = new OkHttpClient.Builder().addInterceptor(chain -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "平台请求不能占用数据库事务");
            VideoVisibilityRestoreTask restoring = tasks.findById(restoringTaskId).orElseThrow();
            assertEquals("RESTORING", restoring.getState(), "平台请求前必须提交领取任务的状态");
            assertEquals(1, restoring.getAttemptCount());
            requestCount.incrementAndGet();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(ResponseBody.create(platformResponse, MediaType.get("application/json")))
                    .build();
        }).build();
        ReflectionTestUtils.setField(HttpClientUtil.class, "client", testClient);
    }

    @AfterEach
    void restoreHttpClient() {
        if (originalClient != null) ReflectionTestUtils.setField(HttpClientUtil.class, "client", originalClient);
    }

    @Test
    void immediateRestoreCommitsCompletionWithoutCallerTransaction() {
        VideoVisibilityRestoreTask task = pendingTask(true);

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertTrue(service.restoreNow(task.getId()));

        assertCompleted(task.getId());
        assertEquals(1, requestCount.get());
        assertFalse(service.restoreNow(task.getId()));
        assertEquals(1, requestCount.get(), "已完成任务不能重复调用平台");
    }

    @Test
    void scheduledRetryCommitsCompletionWithoutCallerTransaction() {
        VideoVisibilityRestoreTask task = pendingTask(true);
        // 这里验收到期任务的事务提交，让任务明确到期，避免数据库时间精度影响扫描
        task.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        tasks.save(task);

        service.processDueTasks();

        assertCompleted(task.getId());
        service.processDueTasks();
        assertEquals(1, requestCount.get());
    }

    @Test
    void rejectedPlatformResponsePersistsRetryAndDoesNotRunAgainImmediately() {
        VideoVisibilityRestoreTask task = pendingTask(true);
        platformResponse = "{\"code\":-101,\"message\":\"账号未登录\"}";

        assertFalse(service.restoreNow(task.getId()));

        assertRetryScheduled(task.getId(), "恢复视频可见性失败：账号未登录");
        service.processDueTasks();
        assertEquals(1, requestCount.get());
    }

    @Test
    void loggedOutAccountPersistsRetryWithoutCallingPlatform() {
        VideoVisibilityRestoreTask task = pendingTask(false);
        // 这里验收未登录账号的重试持久化，让到期条件明确，避免依赖数据库时间精度
        task.setNextAttemptAt(LocalDateTime.now().minusSeconds(1));
        tasks.save(task);

        service.processDueTasks();

        assertRetryScheduled(task.getId(), "原投稿账号未登录，等待重新登录后恢复视频状态");
        assertEquals(0, requestCount.get());
    }

    private VideoVisibilityRestoreTask pendingTask(boolean loggedIn) {
        RecordHistory history = histories.save(new RecordHistory());
        BiliBiliUser user = new BiliBiliUser();
        user.setLogin(loggedIn);
        user.setCookies("bili_jct=test-csrf; SESSDATA=test-session;");
        user = users.save(user);
        VideoVisibilityRestoreTask task = service.ensurePreparing(history.getId(), user.getId(), "12345", 1);
        assertTrue(service.markVideoPublic(task.getId()));
        service.requestRestore(task.getId());
        restoringTaskId = task.getId();
        return tasks.findById(task.getId()).orElseThrow();
    }

    private void assertCompleted(Long taskId) {
        VideoVisibilityRestoreTask stored = tasks.findById(taskId).orElseThrow();
        assertEquals("COMPLETE", stored.getState());
        assertEquals(1, stored.getAttemptCount());
        assertNull(stored.getNextAttemptAt());
        assertNull(stored.getErrorMessage());
    }

    private void assertRetryScheduled(Long taskId, String message) {
        VideoVisibilityRestoreTask stored = tasks.findById(taskId).orElseThrow();
        assertEquals("PENDING", stored.getState());
        assertEquals(1, stored.getAttemptCount());
        assertEquals(message, stored.getErrorMessage());
        assertTrue(stored.getNextAttemptAt().isAfter(LocalDateTime.now()));
    }
}
