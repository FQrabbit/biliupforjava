package top.sshh.bililiverecoder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.service.CaptchaService;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/captcha")
public class CaptchaController {

    @Autowired
    private CaptchaService captchaService;

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("required", captchaService.isCaptchaRequired());
        if (captchaService.isCaptchaRequired()) {
            status.put("voucher", captchaService.getVoucher());
            status.put("filename", captchaService.getFilename());
            status.put("extra", captchaService.getExtraInfo());
        }
        return status;
    }

    @PostMapping("/submit")
    public String submitCaptcha(@RequestBody Map<String, String> result) {
        captchaService.submitCaptcha(result);
        return "success";
    }
}
