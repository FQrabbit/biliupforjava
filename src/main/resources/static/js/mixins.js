/**
 * mixins.js — Vue 全局方法混入
 *
 * 提供给所有Vue实例的全局方法：
 *   - showMessage：显示消息提示框
 *   - setLoading：更新组件的loading状态
 *
 * 依赖：Vue 2.x
 */

Vue.mixin({
    methods: {
        showMessage: function(message, type) {
            this.$message({
                message: message,
                type: type || 'info'
            });
        },

        setLoading: function(isLoading) {
            this.loading = isLoading;
        }
    }
});
