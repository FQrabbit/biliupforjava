(function(window) {
    'use strict';

    function createOptions(template) {
        return {
        template: template,
        props: {
            // 绑定的 channel draft 对象，支持 v-model
            value: {
                type: Object,
                required: true
            },
            // 对应 el-input / el-select 的 size，新增表单传 'small'，编辑行传 'mini'
            size: {
                type: String,
                default: 'small'
            },
            // 区分新增和编辑场景，密码字段的 placeholder 会不一样
            isNew: {
                type: Boolean,
                default: false
            },
            // 渠道类型选项列表，从父级传进来保持同步
            typeOptions: {
                type: Array,
                default: function() { return []; }
            }
        },
        computed: {
            // 根节点需要挂的 class，由渠道类型动态决定
            // 新增表单用 notification-channel-form + is-xxx 控制 grid 布局
            // 编辑表格用 notification-inline-fields + notification-inline-fields-xxx
            rootClass: function() {
                var type = this.value && this.value.type;
                if (this.isNew) {
                    return {
                        'notification-channel-form': true,
                        'is-wecom': type === 'wecom_app',
                        'is-ntfy': type === 'ntfy',
                        'is-dingtalk': type === 'dingtalk_webhook'
                    };
                }
                return {
                    'notification-inline-fields': true,
                    'notification-inline-fields-wecom': type === 'wecom_app',
                    'notification-inline-fields-ntfy': type === 'ntfy',
                    'notification-inline-fields-dingtalk': type === 'dingtalk_webhook'
                };
            },
            // 内部分组 wrapper 的 class：两个场景命名不同
            // 新增表单 → notification-channel-group（配合 group-xxx 子 class 定位 grid 位置）
            // 编辑表格 → notification-inline-cluster（固定3列 grid）
            groupBaseClass: function() {
                return this.isNew ? 'notification-channel-group' : 'notification-inline-cluster';
            }
        },
        methods: {
            // 更新某个字段并往上冒 input 事件，让 v-model 能正常工作
            update: function(key, val) {
                var next = Object.assign({}, this.value);
                next[key] = val;
                this.$emit('input', next);
            }
        }
        };
    }

    window.BiliupNotificationChannelFields = {
        createOptions: createOptions
    };

})(window);
