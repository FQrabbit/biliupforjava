/**
 * 用户管理页组件
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
            avatarErrors: {},
            mobileUserActionsVisible: false,
            mobileActionUser: null
        };
    },
    computed: {
        totalUserCount: function () {
            return this.tableData.length;
        },
        loggedInUserCount: function () {
            return this.tableData.filter(function (item) {
                return item && item.login;
            }).length;
        },
        enabledUserCount: function () {
            return this.tableData.filter(function (item) {
                return item && item.login && item.enable;
            }).length;
        },
        expiredUserCount: function () {
            return this.tableData.filter(function (item) {
                return item && !item.login;
            }).length;
        },
        mobileUserHeroText: function () {
            if (this.totalUserCount === 0) {
                return '还没有关联账号';
            }
            return this.loggedInUserCount + ' 个账号可用，' + this.enabledUserCount + ' 个参与弹幕分摊';
        },
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
            UserApi.list(function (data) {
                self.tableData = Array.isArray(data) ? data : [];
                if (self.mobileActionUser) {
                    var actionKey = self.mobileUserKey(self.mobileActionUser);
                    var matched = self.tableData.filter(function (item) {
                        return self.mobileUserKey(item) === actionKey;
                    })[0];
                    if (matched) {
                        self.mobileActionUser = matched;
                    } else {
                        self.closeMobileUserActions();
                    }
                }
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

            UserApi.loginQr(function (data) {
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

                UserApi.loginCheck(self.loginKey, function (data) {
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
                UserApi.loginCancel(self.loginKey, function () {});
                self.loginKey = '';
            }
            self.dialogLoginVisible = false;
            self.fetchUserList();
        },
        handleEdit: function (index, row) {
            this.user = JSON.parse(JSON.stringify(row));
            this.dialogFormVisible = true;
        },
        openMobileLogin: function () {
            this.closeMobileUserActions();
            this.getLoginImage();
            this.dialogLoginVisible = true;
        },
        mobileUserKey: function (item) {
            if (!item) return '';
            if (item.id !== undefined && item.id !== null) return 'id:' + item.id;
            if (item.uid !== undefined && item.uid !== null) return 'uid:' + item.uid;
            return '';
        },
        toggleMobileUserActions: function (item) {
            if (!item) return;
            var nextKey = this.mobileUserKey(item);
            var currentKey = this.mobileUserKey(this.mobileActionUser);
            if (this.mobileUserActionsVisible && nextKey && nextKey === currentKey) {
                this.closeMobileUserActions();
                return;
            }
            this.mobileActionUser = item;
            this.mobileUserActionsVisible = true;
        },
        closeMobileUserActions: function () {
            this.mobileUserActionsVisible = false;
            this.mobileActionUser = null;
        },
        openMobileUserEdit: function (item) {
            if (!item) return;
            this.closeMobileUserActions();
            this.handleEdit(0, item);
        },
        refreshMobileUserProfile: function (item) {
            if (!item) return;
            this.closeMobileUserActions();
            this.refreshUserProfile(item);
        },
        deleteMobileUser: function (item) {
            var self = this;
            if (!item || !item.id) return;
            var name = self.maskText(item.uname || '该账号');
            self.$confirm('确定移除账号「' + name + '」吗？', '移除账号', {
                confirmButtonText: '移除',
                cancelButtonText: '取消',
                type: 'warning',
                confirmButtonClass: 'el-button--danger'
            }).then(function () {
                self.closeMobileUserActions();
                self.deleteUser(item.id);
            }).catch(function () {});
        },
        mobileUserInitial: function (item) {
            if (this.privacyMode) return '*';
            var name = item && item.uname ? String(item.uname) : 'U';
            return name.charAt(0).toUpperCase();
        },
        mobileUserStateClass: function (item) {
            if (!item || !item.login) return 'is-danger';
            if (item.enable) return 'is-success';
            return 'is-muted';
        },
        mobileUserStateLabel: function (item) {
            if (!item || !item.login) return '未登录';
            if (item.enable) return '已启用';
            return '已登录';
        },
        mobileUserTimeText: function (item) {
            if (!item || !item.updateTime) return '暂无记录';
            return item.updateTime;
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
            UserApi.refresh(item.id, function (data) {
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
            UserApi.update(self.user, function (data) {
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
            UserApi.remove(id, function (data) {
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
