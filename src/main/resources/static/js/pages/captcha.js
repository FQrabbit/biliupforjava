/**
 * 验证码页入口
 */
new Vue({
    el: '#app',
    data: {
        loading: true,
        required: false,
        challenges: [],
        requestId: '',
        voucher: '',
        filename: '',
        extra: {},
        captchaObj: null,
        captchaResult: null,
        captchaSuccess: false,
        submitting: false,
        timer: null,
        challengeVersion: 0,
        statusRequestVersion: 0,
        selectedChallenge: null,
        manualJson: '',
        // 自动生成的 Hook 脚本，动态插入当前服务器地址
        hookScript: `(function(){
    var targetUrl = "${window.location.origin}/captcha/submit";
    console.log("正在监听 B站验证码请求...");

    // 监听 fetch
    var originalFetch = window.fetch;
    window.fetch = function(input, init) {
        if (typeof input === 'string' && (input.includes('add/v3') || input.includes('validate'))) {
            // 尝试从 URL 或 Body 中提取 token
            try {
                var token = null;
                if (input.includes('captcha_token=')) {
                    token = input.match(/captcha_token=([^&]+)/)[1];
                } else if (init && init.body) {
                    if (init.body.includes('captcha_token')) {
                        var body = JSON.parse(init.body);
                        token = body.captcha_token;
                    }
                }

                if (token) {
                    console.log("捕获到 Token: " + token);
                    var data = { captcha_token: token };
                    // 使用 sendBeacon 或 XHR 跨域发送 (需要后端支持 CORS，或者手动复制)
                    // 这里为了简单，直接弹窗提示用户复制
                    prompt("捕获到验证码 Token，请全选复制并填入工具的【手动结果】框中：", JSON.stringify(data));
                }
            } catch(e) { console.error(e); }
        }
        return originalFetch.apply(this, arguments);
    };

    alert("脚本注入成功！请现在上传视频触发验证。");
})();`
    },
    mounted() {
        this.checkStatus();
        this.timer = setInterval(this.checkStatus, 5000);
    },
    beforeDestroy() {
        this.challengeVersion++;
        this.destroyCaptchaInstance(this.captchaObj);
        if (this.timer) clearInterval(this.timer);
    },
    methods: {
        copyScript() {
            const el = document.createElement('textarea');
            el.value = this.hookScript;
            document.body.appendChild(el);
            el.select();
            document.execCommand('copy');
            document.body.removeChild(el);
            this.$message.success('代码已复制到剪贴板');
        },
        checkStatus() {
            const statusVersion = ++this.statusRequestVersion;
            const previousRequestId = this.requestId;
            CaptchaApi.status((res) => {
                if (statusVersion !== this.statusRequestVersion) return;
                this.loading = false;
                this.challenges = res.challenges || [];
                if (res.required) {
                    const selected = this.challenges.find(item => item.requestId === this.requestId) || this.challenges[0];
                    if (selected && (this.requestId !== selected.requestId || this.voucher !== selected.voucher)) {
                        this.requestId = selected.requestId;
                        this.applyChallenge(selected);
                    }
                } else {
                    const previous = (res.recentChallenges || []).find(item => item.requestId === previousRequestId);
                    if (previous && previous.state === 'EXPIRED') this.$message.warning('验证码已过期，请重新触发验证码');
                    if (previous && previous.state === 'CANCELLED') this.$message.info('验证码任务已取消');
                    this.required = false;
                    this.requestId = '';
                    this.voucher = '';
                    this.filename = '';
                    this.selectedChallenge = null;
                    this.captchaSuccess = false;
                    this.captchaResult = null;
                    this.manualJson = '';
                    this.destroyCaptchaInstance(this.captchaObj);
                }
            });
        },
        selectChallenge(requestId) {
            const selected = this.challenges.find(item => item.requestId === requestId);
            if (selected) this.applyChallenge(selected);
        },
        applyChallenge(selected) {
            this.challengeVersion++;
            const version = this.challengeVersion;
            const requestId = selected.requestId;
            this.required = true;
            this.requestId = requestId;
            this.selectedChallenge = selected;
            this.submitting = false;
            this.voucher = selected.voucher;
            this.filename = selected.filename;
            this.extra = selected.extra || {};
            this.captchaSuccess = false;
            this.captchaResult = null;
            this.manualJson = '';
            this.destroyCaptchaInstance(this.captchaObj);
            this.captchaObj = null;
            this.$nextTick(() => {
                if (version === this.challengeVersion && requestId === this.requestId) this.initCaptcha(version, requestId);
            });
        },
        destroyCaptchaInstance(instance) {
            if (instance && typeof instance.destroy === 'function') {
                try { instance.destroy(); } catch (e) { console.debug('验证码组件清理失败', e); }
            }
            $('#captcha-box').empty();
        },
        initCaptcha(version, requestId) {

            console.log("Extra info:", this.extra);

            // B站投稿通常使用固定的 captchaId
            const BILI_UPLOAD_CAPTCHA_ID = 'a431eaf5e5dadd28bc0553c29682bd4b';

            // V4 初始化逻辑 (默认尝试)
            initGeetest4({
                captchaId: BILI_UPLOAD_CAPTCHA_ID,
                product: 'popup'
            }, (captchaObj) => {
                if (version !== this.challengeVersion || requestId !== this.requestId) {
                    this.destroyCaptchaInstance(captchaObj);
                    return;
                }
                this.captchaObj = captchaObj;
                captchaObj.appendTo("#captcha-box");
                captchaObj.onSuccess(() => {
                    if (version !== this.challengeVersion || requestId !== this.requestId) return;
                    let result = captchaObj.getValidate();
                    console.log("Geetest V4 Result:", result);

                    this.captchaResult = {
                        // V4 标准参数
                        lot_number: result.lot_number,
                        pass_token: result.pass_token,
                        gen_time: result.gen_time,
                        captcha_output: result.captcha_output,
                        captcha_id: BILI_UPLOAD_CAPTCHA_ID
                    };
                    this.captchaSuccess = true;
                });
                captchaObj.onError((e) => {
                    if (version !== this.challengeVersion || requestId !== this.requestId) return;
                    console.error("Geetest V4 Error:", e);
                    this.$message.error("验证码加载失败，请尝试手动处理");
                });
            });
        },
        submitCaptcha() {
            if (!this.captchaResult) return;
            this.doSubmit(this.captchaResult, this.requestId, this.challengeVersion);
        },
        submitManual() {
            if (!this.manualJson) {
                this.$message.warning('请输入JSON内容');
                return;
            }
            try {
                let result = JSON.parse(this.manualJson);
                this.doSubmit(result, this.requestId, this.challengeVersion);
            } catch (e) {
                this.$message.error('JSON格式错误');
            }
        },
        submitRetry() {
            this.$confirm('确认已在浏览器完成验证？程序将尝试重新发起上传请求。', '提示', {
                confirmButtonText: '确定',
                cancelButtonText: '取消',
                type: 'warning'
            }).then(() => {
                this.doSubmit({});
            });
        },
        cancelChallenge() {
            const requestId = this.requestId;
            if (!requestId) return;
            CaptchaApi.cancel(requestId, (res) => {
                if (!res || !res.success) {
                    this.$message.warning('验证码任务已结束或无法取消');
                    this.checkStatus();
                    return;
                }
                this.$message.success('已取消此验证码任务');
                this.checkStatus();
            }, () => this.$message.error('取消验证码失败'));
        },
        doSubmit(data, requestId, version) {
            const targetRequestId = requestId || this.requestId;
            const targetVersion = version === undefined ? this.challengeVersion : version;
            this.submitting = true;
            CaptchaApi.submit(Object.assign({}, data, { requestId: targetRequestId }), (res) => {
                if (targetVersion !== this.challengeVersion) return;
                if (!res || !res.success) {
                    this.$message.error(res && res.message ? res.message : '验证码任务已结束，请刷新后重试');
                    this.submitting = false;
                    this.checkStatus();
                    return;
                }
                this.$message.success('提交成功，上传将继续');
                this.submitting = false;
                this.required = false; // 暂时隐藏，等待下一次轮询确认
                this.captchaSuccess = false;
                this.manualJson = '';
            }, (xhr) => {
                if (targetVersion !== this.challengeVersion) return;
                this.$message.error('提交失败');
                this.submitting = false;
            });
        }
    }
});
