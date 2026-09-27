package top.sshh.bililiverecoder.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import top.sshh.bililiverecoder.service.CaptchaService;
import top.sshh.bililiverecoder.service.PublishAccountScheduler;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/captcha")
public class CaptchaController {

    @Autowired
    private CaptchaService captchaService;

    @Autowired
    private PublishAccountScheduler publishAccountScheduler;

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("required", captchaService.isCaptchaRequired());
        status.put("challenges", captchaService.pendingChallenges());
        status.put("recentChallenges", captchaService.recentChallenges());
        if (captchaService.isCaptchaRequired()) {
            status.put("voucher", captchaService.getVoucher());
            status.put("filename", captchaService.getFilename());
            status.put("extra", captchaService.getExtraInfo());
        }
        return status;
    }

    @GetMapping("/status/{requestId}")
    public Map<String, Object> getChallengeStatus(@PathVariable String requestId) {
        Map<String, Object> result = new HashMap<>();
        result.put("challenge", captchaService.challengeStatus(requestId));
        return result;
    }

    @PostMapping("/submit")
    public Map<String, Object> submitCaptcha(@RequestBody Map<String, String> result) {
        String requestId = result.remove("requestId");
        boolean accepted = requestId == null
                ? captchaService.submitCaptcha(result)
                : captchaService.submitCaptcha(requestId, result);
        Map<String, Object> response = new HashMap<>();
        response.put("success", accepted);
        CaptchaService.ChallengeStatus challenge = requestId == null
                ? null : captchaService.challengeStatus(requestId);
        response.put("state", challenge == null ? null : challenge.state());
        response.put("message", accepted ? "验证码结果已接收"
                : challenge != null && "SUBMITTED".equals(challenge.state())
                ? "验证码结果已保存，但恢复回调未完成，可再次提交以恢复任务"
                : "验证码任务已过期、取消或无法识别");
        return response;
    }

    @DeleteMapping("/{requestId}")
    public Map<String, Object> cancelCaptcha(@PathVariable String requestId) {
        CaptchaService.ChallengeStatus challenge = captchaService.challengeStatus(requestId);
        boolean cancelled = captchaService.cancel(requestId);
        if (cancelled) publishAccountScheduler.captchaCancelled(challenge);
        return Map.of("success", cancelled);
    }
}
