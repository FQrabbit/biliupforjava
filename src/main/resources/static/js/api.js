/**
 * api.js — 全局 API 工具层
 *
 * 包含内容：
 *   - $.ajaxSetup：全局请求拦截器，自动注入Authorization并处理401重定向
 *   - ApiUtil：GET/POST/PUT/DELETE/fetchBlob等通用请求方法
 *   - COMMON_BOOL_OPTIONS：通用布尔值选项（是/否）
 *
 * 依赖：jQuery 3.x
 */

// 全局配置：每次请求前检查 localStorage 是否有 token，有则带上
$.ajaxSetup({
    beforeSend: function(xhr) {
        const token = localStorage.getItem('biliup_auth');
        if (token) {
            xhr.setRequestHeader('Authorization', token);
        }
    }
});

const ApiUtil = {
    fetchBlob: function(url, options) {
        var token = null;
        try {
            token = localStorage.getItem('biliup_auth');
        } catch (e) {
        }
        var requestOptions = Object.assign({}, options || {});
        var headers = requestOptions.headers || {};
        if (token) {
            headers = Object.assign({}, headers, { 'Authorization': token });
        }
        requestOptions.headers = headers;
        return fetch(url, Object.assign({
            method: 'GET',
            cache: 'no-store'
        }, requestOptions)).then(function (res) {
            if (res.status === 401) {
                window.location.href = '/html/login.html';
                throw new Error('unauthorized');
            }
            if (!res.ok) {
                if (options && typeof options.handleError === 'function') {
                    return options.handleError(res).then(function (err) {
                        throw err;
                    });
                }
                throw new Error('bad_response');
            }
            var ct = res.headers && res.headers.get ? res.headers.get('content-type') : '';
            if (!(options && options.acceptAnyBlob) && ct && ct.indexOf('image/') !== 0) {
                throw new Error('non_image');
            }
            return res.blob().then(function (blob) {
                if (options && options.acceptAnyBlob) {
                    return { blob: blob, headers: res.headers };
                }
                return blob;
            });
        });
    },
    get: function(url, callback, errorCallback) {
        $.ajax({
            url: url,
            type: 'GET',
            dataType: 'json',
            success: function(data) {
                callback(data);
            },
            error: function(xhr, status, error) {
                if (xhr.status === 401) {
                    window.location.href = '/html/login.html';
                    return;
                }
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
                if (xhr.status === 401) {
                    window.location.href = '/html/login.html';
                    return;
                }
                if (errorCallback) {
                    errorCallback(xhr);
                } else {
                    console.error('Request failed:', error);
                }
            }
        });
    },

    put: function(url, data, callback, errorCallback) {
        $.ajax({
            url: url,
            type: 'PUT',
            contentType: 'application/json;charset=utf-8',
            data: JSON.stringify(data),
            dataType: 'json',
            success: function(result) {
                callback(result);
            },
            error: function(xhr, status, error) {
                if (xhr.status === 401) {
                    window.location.href = '/html/login.html';
                    return;
                }
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
            dataType: 'text',
            success: function(data, textStatus, jqXHR) {
                try {
                    var ct = (jqXHR && jqXHR.getResponseHeader) ? (jqXHR.getResponseHeader('Content-Type') || '') : '';
                    if (ct.indexOf('text/html') >= 0) {
                        window.location.href = '/html/login.html';
                        return;
                    }
                } catch (e) {}

                var result = data;
                try {
                    var ct2 = (jqXHR && jqXHR.getResponseHeader) ? (jqXHR.getResponseHeader('Content-Type') || '') : '';
                    if (ct2.indexOf('application/json') >= 0 && typeof data === 'string' && data) {
                        result = JSON.parse(data);
                    }
                } catch (e) {
                    result = data;
                }
                callback(result);
            },
            error: function(xhr, status, error) {
                if (xhr.status === 401) {
                    window.location.href = '/html/login.html';
                    return;
                }
                if (errorCallback) {
                    errorCallback(xhr);
                } else {
                    console.error('Request failed:', error);
                }
            }
        });
    }
};

const COMMON_BOOL_OPTIONS = [
    { label: '是', value: true },
    { label: '否', value: false }
];
