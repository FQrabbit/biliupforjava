package top.sshh.bililiverecoder;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.hikari.jdbc-url=jdbc:h2:mem:biliupforjava-app-context;DB_CLOSE_DELAY=-1",
        "record.work-path=${java.io.tmpdir}/biliupforjava-app-context"
})
class BiliLiveRecoderApplicationTests {

    @Test
    void contextLoads() {
    }

}
