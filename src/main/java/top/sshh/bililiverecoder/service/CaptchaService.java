package top.sshh.bililiverecoder.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Map;

@Service
public class CaptchaService {
    private volatile boolean captchaRequired = false;
    private volatile String voucher;
    private volatile String filename;
    private volatile Map<String, Object> extraInfo;
    private CountDownLatch latch;
    private Map<String, String> result;

    public void setCaptchaRequired(String voucher, String filename, Map<String, Object> extraInfo) {
        this.voucher = voucher;
        this.filename = filename;
        this.extraInfo = extraInfo;
        this.captchaRequired = true;
        this.latch = new CountDownLatch(1);
        this.result = null;
    }

    public boolean isCaptchaRequired() {
        return captchaRequired;
    }

    public String getVoucher() {
        return voucher;
    }
    
    public String getFilename() {
        return filename;
    }

    public Map<String, Object> getExtraInfo() {
        return extraInfo;
    }

    public Map<String, String> waitForCaptcha() {
        try {
            if (latch != null) {
                // Wait for up to 5 minutes
                boolean success = latch.await(300, TimeUnit.SECONDS);
                if (success) {
                    return result;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            this.captchaRequired = false;
        }
        return null;
    }

    public void submitCaptcha(Map<String, String> result) {
        this.result = result;
        this.captchaRequired = false;
        if (latch != null) {
            latch.countDown();
        }
    }
}
