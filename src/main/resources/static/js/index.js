const ApiUtil = {
    get: function(url, callback, errorCallback) {
        $.ajax({
            url: url,
            type: 'GET',
            dataType: 'json',
            success: function(data) {
                callback(data);
            },
            error: function(xhr, status, error) {
                if (errorCallback) {
                    errorCallback(xhr);
                } else {
                    console.error('Request failed:', error);
                }
            }
        });
    },

    post: function(url, data, callback, errorCallback) {
        $.ajax({
            url: url,
            type: 'POST',
            contentType: 'application/json;charset=utf-8',
            data: JSON.stringify(data),
            dataType: 'json',
            success: function(result) {
                callback(result);
            },
            error: function(xhr, status, error) {
                if (errorCallback) {
                    errorCallback(xhr);
                } else {
                    console.error('Request failed:', error);
                }
            }
        });
    },

    delete: function(url, callback, errorCallback) {
        $.ajax({
            url: url,
            type: 'DELETE',
            dataType: 'json',
            success: function(data) {
                callback(data);
            },
            error: function(xhr, status, error) {
                if (errorCallback) {
                    errorCallback(xhr);
                } else {
                    console.error('Request failed:', error);
                }
            }
        });
    }
};

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

const COMMON_BOOL_OPTIONS = [
    { label: '是', value: true },
    { label: '否', value: false }
];
