/**
 * 验证码页入口
 */
new Vue({
    el: '#app',
    data: {
        loading: true,
        required: false,
        voucher: '',
        filename: '',
        extra: {},
        captchaObj: null,
        captchaResult: null,
        captchaSuccess: false,
        submitting: false,
        timer: null,
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
            if (this.required && !this.captchaSuccess && !this.manualJson) return; // 如果正在验证中且未手动输入，不要刷新状态

            CaptchaApi.status((res) => {
                this.loading = false;
                if (res.required) {
                    if (this.voucher !== res.voucher) {
                        this.required = true;
                        this.voucher = res.voucher;
                        this.filename = res.filename;
                        this.extra = res.extra || {};
                        this.initCaptcha();
                    }
                } else {
                    this.required = false;
                    this.voucher = '';
                    this.filename = '';
                    this.captchaSuccess = false;
                    this.captchaResult = null;
                    this.manualJson = '';
                    if (this.captchaObj) {
                        // 清理旧的验证码实例
                        $('#captcha-box').empty();
                        this.captchaObj = null;
                    }
                }
            });
        },
        initCaptcha() {

            console.log("Extra info:", this.extra);

            // B站投稿通常使用固定的 captchaId
            const BILI_UPLOAD_CAPTCHA_ID = 'a431eaf5e5dadd28bc0553c29682bd4b';

            // V4 初始化逻辑 (默认尝试)
            initGeetest4({
                captchaId: BILI_UPLOAD_CAPTCHA_ID,
                product: 'popup'
            }, (captchaObj) => {
                this.captchaObj = captchaObj;
                captchaObj.appendTo("#captcha-box");
                captchaObj.onSuccess(() => {
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
                    console.error("Geetest V4 Error:", e);
                    this.$message.error("验证码加载失败，请尝试手动处理");
                });
            });
        },
        submitCaptcha() {
            if (!this.captchaResult) return;
            this.doSubmit(this.captchaResult);
        },
        submitManual() {
            if (!this.manualJson) {
                this.$message.warning('请输入JSON内容');
                return;
            }
            try {
                let result = JSON.parse(this.manualJson);
                this.doSubmit(result);
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
        doSubmit(data) {
            this.submitting = true;

            CaptchaApi.submit(data, (res) => {
                this.$message.success('提交成功，上传将继续');
                this.submitting = false;
                this.required = false; // 暂时隐藏，等待下一次轮询确认
                this.captchaSuccess = false;
                this.manualJson = '';
            }, (xhr) => {
                this.$message.error('提交失败');
                this.submitting = false;
            });
        }
    }
});
