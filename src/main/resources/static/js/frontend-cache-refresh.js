(function(window) {
    'use strict';

    var STORED_BUILD_KEY = 'biliup_frontend_build_id';
    var STORED_VERSION_KEY = 'biliup_frontend_version';
    var DEFAULT_INTERVAL_MS = 30000;

    var state = {
        timerId: null,
        inFlight: null,
        needRefresh: false,
        options: {},
        listenersBound: false
    };

    function getStoredValue(key) {
        try {
            return window.localStorage ? window.localStorage.getItem(key) : null;
        } catch (e) {
            return null;
        }
    }

    function setStoredValue(key, value) {
        try {
            if (window.localStorage) {
                window.localStorage.setItem(key, value);
            }
        } catch (e) {
        }
    }

    function getPageBuildId() {
        return window.BILIUPFORJAVA_FRONTEND_BUILD_ID || '';
    }

    function resolveAppUrl(url) {
        if (window.BiliupUrlResolver && typeof window.BiliupUrlResolver.resolve === 'function') {
            return window.BiliupUrlResolver.resolve(url);
        }
        return url;
    }

    function withBuildId(url, buildId) {
        url = resolveAppUrl(url);
        var id = buildId || getPageBuildId() || getStoredValue(STORED_BUILD_KEY) || '';
        if (!id || !url || /^(https?:)?\/\//i.test(url) || /^data:/i.test(url) || /^blob:/i.test(url)) {
            return url;
        }
        var hash = '';
        var hashIndex = url.indexOf('#');
        if (hashIndex >= 0) {
            hash = url.substring(hashIndex);
            url = url.substring(0, hashIndex);
        }
        var parts = url.split('?');
        var path = parts[0];
        var query = parts.length > 1 ? parts.slice(1).join('?') : '';
        var params = new URLSearchParams(query);
        params.set('v', id);
        return path + '?' + params.toString() + hash;
    }

    function getReloadTarget(buildId, locationLike) {
        var currentLocation = locationLike || window.location;
        return withBuildId(currentLocation.pathname + currentLocation.search + currentLocation.hash, buildId);
    }

    function reload(buildId) {
        window.location.replace(getReloadTarget(buildId));
    }

    function fetchVersion() {
        return window.fetch(resolveAppUrl('/api/version'), {
            method: 'GET',
            headers: {
                'Accept': 'application/json'
            },
            cache: 'no-store',
            credentials: 'same-origin'
        }).then(function(response) {
            if (!response.ok) {
                throw response;
            }
            return response.json();
        });
    }

    function shouldRefresh(buildId) {
        var pageBuildId = getPageBuildId();
        var storedBuildId = getStoredValue(STORED_BUILD_KEY);
        return pageBuildId ? pageBuildId !== buildId : storedBuildId !== buildId;
    }

    function applyVersionData(data, options) {
        var opts = options || {};
        var version = data && data.version ? data.version : data;
        var buildId = data && data.buildId ? data.buildId : version;
        if (!version || version === 'unknown' || version === 'error') {
            return false;
        }
        if (!buildId || buildId === 'unknown' || buildId === 'error') {
            return false;
        }

        var pageBuildId = getPageBuildId();
        var storedBuildId = getStoredValue(STORED_BUILD_KEY);
        var refreshNeeded = shouldRefresh(buildId);

        setStoredValue(STORED_BUILD_KEY, buildId);
        setStoredValue(STORED_VERSION_KEY, version);

        if (!refreshNeeded) {
            return false;
        }

        state.needRefresh = true;
        if (typeof opts.onRefreshNeeded === 'function') {
            opts.onRefreshNeeded({
                version: version,
                buildId: buildId,
                pageBuildId: pageBuildId,
                storedBuildId: storedBuildId
            });
        } else {
            reload(buildId, opts);
        }
        return true;
    }

    function mergeOptions(options) {
        state.options = Object.assign({}, state.options, options || {});
        return state.options;
    }

    function check(options) {
        var opts = mergeOptions(options);
        if (state.needRefresh) {
            return Promise.resolve(true);
        }
        if (state.inFlight) {
            return state.inFlight;
        }

        state.inFlight = fetchVersion()
                .then(function(data) {
                    return applyVersionData(data, opts);
                })
                .catch(function(error) {
                    if (window.console && console.warn) {
                        console.warn('获取前端版本失败:', error && error.status ? error.status : error);
                    }
                    return false;
                })
                .finally(function() {
                    state.inFlight = null;
                });

        return state.inFlight;
    }

    function bindVisibilityChecks() {
        if (state.listenersBound) {
            return;
        }
        state.listenersBound = true;
        document.addEventListener('visibilitychange', function() {
            if (!document.hidden) {
                check();
            }
        });
        window.addEventListener('focus', function() {
            check();
        });
    }

    function start(options) {
        var opts = mergeOptions(options);
        var intervalMs = opts.intervalMs || DEFAULT_INTERVAL_MS;
        if (state.timerId) {
            clearInterval(state.timerId);
        }
        bindVisibilityChecks();
        check(opts);
        state.timerId = window.setInterval(function() {
            check(opts);
        }, intervalMs);
    }

    function stop() {
        if (state.timerId) {
            clearInterval(state.timerId);
            state.timerId = null;
        }
    }

    window.FrontendCacheRefresh = {
        check: check,
        start: start,
        stop: stop,
        reload: reload,
        withBuildId: withBuildId,
        getReloadTarget: getReloadTarget,
        getPageBuildId: getPageBuildId
    };
})(window);
