package top.sshh.bililiverecoder.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import jakarta.persistence.EntityManager;
import top.sshh.bililiverecoder.entity.BiliBiliUser;
import top.sshh.bililiverecoder.repo.BiliUserRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest(properties = {
        "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:publish-cooldown;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;DATABASE_TO_UPPER=false",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PublishAccountCooldownService.class)
class PublishAccountCooldownPersistenceTest {
    @Autowired BiliUserRepository users;
    @Autowired PublishAccountCooldownService cooldowns;
    @Autowired EntityManager entityManager;

    @Test
    void cooldownSurvivesEntityReload() {
        BiliBiliUser account = users.save(new BiliBiliUser());
        cooldowns.recordRisk(account.getId());
        entityManager.flush();
        entityManager.clear();

        BiliBiliUser reloaded = users.findById(account.getId()).orElseThrow();
        assertEquals(1, reloaded.getPublishRiskFailures());
        assertTrue(cooldowns.waitMs(account.getId()) > 4 * 60_000L);
    }
}
