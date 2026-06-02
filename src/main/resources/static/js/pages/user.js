/**
 * pages/user.js — 用户管理页面组件
 *
 * 功能：
 *   - 用户列表展示和管理
 *   - 二维码登录流程
 *   - 用户信息编辑和保存
 *
 * 事件：
 *   @connection-status：连接状态变化（断开/正常）
 *
 * 依赖：api.js, mixins.js, privacy.js
 */

Vue.component('user-page', {
    template: '#user-template',
    data: function () {
        return {
            dialogFormVisible: false,
            dialogLoginVisible: false,
            tableData: [],
            user: {},
            image: '',
            loginKey: '',
            loginCheckTimer: null,
            loginLoading: false,
            saveLoading: false,
            loginStatus: 'pending', // 待定、已扫码、成功、已过期、失败
            loading: false,
            isMobile: window.innerWidth <= 768,
            viewMode: 'card',
            avatarErrors: {}
        };
    },
    computed: {
        loginStatusText: function () {
            switch (this.loginStatus) {
                case 'pending': return '等待扫码，二维码有效期5分钟';
                case 'scanned': return '已扫码，请在手机上确认登录';
                case 'success': return '登录成功！';
                case 'expired': return '二维码已过期，请刷新';
                case 'failed': return '登录失败，请重试';
                default: return '等待扫码';
            }
        },
        loginStatusColor: function () {
            switch (this.loginStatus) {
                case 'pending': return 'var(--text-disabled)';
                case 'scanned': return '#e6a23c';
                case 'success': return '#67c23a';
                case 'expired':
                case 'failed': return '#f56c6c';
                default: return 'var(--text-disabled)';
            }
        },
        loginStatusIcon: function () {
            switch (this.loginStatus) {
                case 'pending': return 'el-icon-time';
                case 'scanned': return 'el-icon-loading';
                case 'success': return 'el-icon-success';
                case 'expired': return 'el-icon-warning';
                case 'failed': return 'el-icon-error';
                default: return 'el-icon-time';
            }
        }
    },
    watch: {
        viewMode: function (val) {
            localStorage.setItem('user-view-mode', val);
        }
    },
    methods: {
        handleResize: function () {
            this.isMobile = window.innerWidth <= 768;
        },
        fetchUserList: function () {
            var self = this;
            self.loading = true;
            ApiUtil.get('/biliUser/list', function (data) {
                self.tableData = data;
                self.loading = false;
                self.$nextTick(function () {
                    self.$emit('connection-status', false);
                });
            }, function () {
                self.loading = false;
                self.$message.error('获取用户列表失败');
                self.$emit('connection-status', false);
            });
        },
        getLoginImage: function () {
            var self = this;
            self.stopLoginCheck();
            self.loginLoading = true;
            self.loginStatus = 'pending';

            ApiUtil.get('/biliUser/login', function (data) {
                self.loginLoading = false;
                if (data.error) {
                    self.$message.error(data.error);
                    return;
                }
                self.image = data.image;
                self.loginKey = data.key;
                self.loginStatus = 'pending';
                self.startLoginCheck();
            }, function () {
                self.loginLoading = false;
                self.$message.error('获取登录二维码失败');
            });
        },
        startLoginCheck: function () {
            var self = this;
            self.loginCheckTimer = setInterval(function () {
                if (!self.loginKey) return;

                ApiUtil.get('/biliUser/loginCheck?key=' + self.loginKey, function (data) {
                    self.loginStatus = data.status;

                    if (data.status === 'success') {
                        self.stopLoginCheck();
                        self.$message.success(data.message || '登录成功');
                        self.fetchUserList();
                        self.dialogLoginVisible = false;
                    } else if (data.status === 'expired' || data.status === 'failed') {
                        self.stopLoginCheck();
                        self.$message.warning(data.message || '登录失效，请重新刷新二维码');
                    }
                });
            }, 2000);
        },
        stopLoginCheck: function () {
            if (this.loginCheckTimer) {
                clearInterval(this.loginCheckTimer);
                this.loginCheckTimer = null;
            }
        },
        cancelLogin: function () {
            var self = this;
            self.stopLoginCheck();
            if (self.loginKey) {
                ApiUtil.get('/biliUser/loginCancel?key=' + self.loginKey);
                self.loginKey = '';
            }
            self.dialogLoginVisible = false;
            self.fetchUserList();
        },
        handleEdit: function (index, row) {
            this.user = JSON.parse(JSON.stringify(row));
            this.dialogFormVisible = true;
        },
        buildAvatarProxyUrl: function (url) {
            if (!url) return '';
            var token = localStorage.getItem('biliup_auth');
            var proxyUrl = '/room/image-proxy?kind=avatar&url=' + encodeURIComponent(url);
            if (token) {
                proxyUrl += '&auth=' + encodeURIComponent(token);
            }
            return proxyUrl;
        },
        getAvatarErrorKey: function (item) {
            return (item && item.id ? item.id : 'unknown') + ':' + (item && item.face ? item.face : '');
        },
        shouldShowAvatar: function (item) {
            if (!item || this.privacyMode || !item.face) return false;
            return !this.avatarErrors[this.getAvatarErrorKey(item)];
        },
        handleAvatarError: function (item) {
            if (!item) return;
            this.$set(this.avatarErrors, this.getAvatarErrorKey(item), true);
        },
        refreshUserProfile: function (item) {
            var self = this;
            if (!item || !item.id) return;
            ApiUtil.get('/biliUser/refresh/' + item.id, function (data) {
                if (data && data.success) {
                    self.$message.success(data.msg || '用户信息已更新');
                    self.fetchUserList();
                } else {
                    self.$message.warning((data && data.msg) || '用户信息刷新失败');
                }
            }, function () {
                self.$message.error('用户信息刷新失败');
            });
        },
        updateUser: function () {
            var self = this;
            self.saveLoading = true;
            ApiUtil.post('/biliUser/update', self.user, function (data) {
                self.saveLoading = false;
                self.$message.success('设置已保存');
                self.fetchUserList();
                self.dialogFormVisible = false;
            }, function () {
                self.saveLoading = false;
                self.$message.error('保存设置失败');
            });
        },
        deleteUser: function (id) {
            var self = this;
            ApiUtil.get('/biliUser/delete/' + id, function (data) {
                self.$message({
                    message: data.msg || '移除成功',
                    type: data.type || 'success'
                });
                self.fetchUserList();
            }, function () {
                self.$message.error('移除用户失败');
            });
        }
    },
    created: function () {
        this.fetchUserList();
        this.handleResize();
        window.addEventListener('resize', this.handleResize);
        var cached = localStorage.getItem('user-view-mode');
        if (cached === 'table' || cached === 'card') {
            this.viewMode = cached;
        }
    },
    activated: function () {
        // 从其他 tab 切回时刷新数据
        this.fetchUserList();
    },
    deactivated: function () {
        // 切走时停止登录轮询
        this.stopLoginCheck();
    },
    beforeDestroy: function () {
        this.stopLoginCheck();
        window.removeEventListener('resize', this.handleResize);
    }
});
