(function (window, document) {
    'use strict';

    var MANIFEST_URL = '/modules/manifest.json';
    var manifestPromise = null;
    var modulePromises = Object.create(null);
    var assetPromises = Object.create(null);

    function hasUnsafeDecodedPath(path) {
        var decoded = path.split(/[?#]/)[0];
        while (true) {
            if (decoded.indexOf('\\') >= 0 || decoded.indexOf('//') === 0 || decoded.split('/').some(function (segment) {
                return segment === '..';
            })) {
                return true;
            }
            var next = decodeURIComponent(decoded);
            if (next === decoded) return false;
            decoded = next;
        }
    }

    function safePath(path) {
        if (typeof path !== 'string' || path.charAt(0) !== '/' || path.indexOf('//') === 0 || path.indexOf('\\') >= 0) {
            return false;
        }
        if (/^[a-z][a-z0-9+.-]*:/i.test(path)) {
            return false;
        }
        try {
            if (hasUnsafeDecodedPath(path)) {
                return false;
            }
            var url = new URL(path, window.location.origin);
            if (url.origin !== window.location.origin || url.pathname.charAt(0) !== '/') {
                return false;
            }
            return true;
        } catch (e) {
            return false;
        }
    }

    function assertSafePath(path) {
        if (!safePath(path)) {
            throw new Error('模块资源路径不安全: ' + path);
        }
        return path;
    }

    function resolvePath(path) {
        if (window.BiliupUrlResolver && typeof window.BiliupUrlResolver.resolve === 'function') {
            return window.BiliupUrlResolver.resolve(path);
        }
        return path;
    }

    function withBuildId(path) {
        assertSafePath(path);
        var resolvedPath = resolvePath(path);
        if (window.FrontendCacheRefresh && typeof window.FrontendCacheRefresh.withBuildId === 'function') {
            return window.FrontendCacheRefresh.withBuildId(resolvedPath);
        }
        var buildId = window.BILIUPFORJAVA_FRONTEND_BUILD_ID || '';
        return buildId ? resolvedPath + (resolvedPath.indexOf('?') >= 0 ? '&' : '?') + 'v=' + encodeURIComponent(buildId) : resolvedPath;
    }

    function assetKey(path) {
        return new URL(resolvePath(path), window.location.origin).pathname;
    }

    function fetchJson(path) {
        return window.fetch(withBuildId(path), {
            credentials: 'same-origin',
            headers: { 'Accept': 'application/json' }
        }).then(function (response) {
            if (!response.ok) {
                throw new Error('HTTP ' + response.status + ' ' + path);
            }
            return response.json();
        });
    }

    function fetchText(path) {
        return window.fetch(withBuildId(path), {
            credentials: 'same-origin',
            headers: { 'Accept': 'text/html' }
        }).then(function (response) {
            if (!response.ok) {
                throw new Error('HTTP ' + response.status + ' ' + path);
            }
            return response.text();
        });
    }

    function loadManifest() {
        if (!manifestPromise) {
            manifestPromise = fetchJson(MANIFEST_URL).then(function (manifest) {
                if (!manifest || manifest.version !== 1 || !manifest.pages) {
                    throw new Error('前端模块清单格式不受支持');
                }
                return manifest;
            }).catch(function (error) {
                manifestPromise = null;
                throw error;
            });
        }
        return manifestPromise;
    }

    function findExistingAsset(tagName, attribute, path) {
        var wanted = assetKey(path);
        var nodes = document.querySelectorAll(tagName + '[' + attribute + ']');
        for (var i = 0; i < nodes.length; i++) {
            try {
                if (new URL(nodes[i].getAttribute(attribute), window.location.href).pathname === wanted) {
                    return nodes[i];
                }
            } catch (e) {
            }
        }
        return null;
    }

    function waitForExistingAsset(node, path, type) {
        var state = node.getAttribute('data-biliup-module-load-state');
        if (state !== 'loading') {
            return Promise.resolve(node);
        }
        return new Promise(function (resolve, reject) {
            var onLoad = function () {
                cleanup();
                resolve(node);
            };
            var onError = function () {
                cleanup();
                reject(new Error(type + '加载失败: ' + path));
            };
            var cleanup = function () {
                node.removeEventListener('load', onLoad);
                node.removeEventListener('error', onError);
            };
            node.addEventListener('load', onLoad);
            node.addEventListener('error', onError);
        });
    }

    function loadStyle(path, pageStyle) {
        assertSafePath(path);
        var key = 'style:' + assetKey(path);
        if (assetPromises[key]) {
            return assetPromises[key].then(function (node) {
                if (pageStyle) {
                    node.setAttribute('data-biliup-page-style', 'true');
                }
                return node;
            });
        }
        assetPromises[key] = new Promise(function (resolve, reject) {
            var existing = findExistingAsset('link', 'href', path);
            if (existing) {
                if (pageStyle) {
                    existing.setAttribute('data-biliup-page-style', 'true');
                    existing.media = 'not all';
                }
                waitForExistingAsset(existing, path, '样式').then(resolve, reject);
                return;
            }
            var link = document.createElement('link');
            link.rel = 'stylesheet';
            link.href = withBuildId(path);
            link.setAttribute('data-biliup-module-asset', assetKey(path));
            link.setAttribute('data-biliup-module-load-state', 'loading');
            if (pageStyle) {
                link.setAttribute('data-biliup-page-style', 'true');
                link.media = 'not all';
            }
            link.onload = function () {
                link.setAttribute('data-biliup-module-load-state', 'loaded');
                resolve(link);
            };
            link.onerror = function () {
                if (link.parentNode) link.parentNode.removeChild(link);
                reject(new Error('样式加载失败: ' + path));
            };
            document.head.appendChild(link);
        }).catch(function (error) {
            delete assetPromises[key];
            throw error;
        });
        return assetPromises[key];
    }

    function loadScript(path) {
        assertSafePath(path);
        var key = 'script:' + assetKey(path);
        if (assetPromises[key]) {
            return assetPromises[key];
        }
        assetPromises[key] = new Promise(function (resolve, reject) {
            var existing = findExistingAsset('script', 'src', path);
            if (existing) {
                waitForExistingAsset(existing, path, '脚本').then(resolve, reject);
                return;
            }
            var script = document.createElement('script');
            script.src = withBuildId(path);
            script.async = false;
            script.setAttribute('data-biliup-module-asset', assetKey(path));
            script.setAttribute('data-biliup-module-load-state', 'loading');
            script.onload = function () {
                script.setAttribute('data-biliup-module-load-state', 'loaded');
                resolve(script);
            };
            script.onerror = function () {
                if (script.parentNode) script.parentNode.removeChild(script);
                reject(new Error('脚本加载失败: ' + path));
            };
            document.head.appendChild(script);
        }).catch(function (error) {
            delete assetPromises[key];
            throw error;
        });
        return assetPromises[key];
    }

    function loadSequential(paths, loader) {
        return (paths || []).reduce(function (promise, path) {
            return promise.then(function () { return loader(path); });
        }, Promise.resolve());
    }

    function moduleStyles(config, surface) {
        var styles = config.styles || {};
        return (styles.common || []).concat(styles[surface] || []);
    }

    function moduleFragments(config, surface) {
        var fragments = config.fragments || {};
        var grouped = Object.prototype.hasOwnProperty.call(fragments, 'common')
            || Object.prototype.hasOwnProperty.call(fragments, 'desktop')
            || Object.prototype.hasOwnProperty.call(fragments, 'mobile');
        if (!grouped) return fragments;
        return Object.assign({}, fragments.common || {}, fragments[surface] || {});
    }

    function composeTemplate(template, fragments) {
        var fragmentNames = Object.keys(fragments || {});
        if (fragmentNames.length === 0 || template.indexOf('data-biliup-fragment') < 0) {
            return template;
        }

        var container = document.createElement('template');
        container.innerHTML = template;
        var placeholders = container.content.querySelectorAll('template[data-biliup-fragment]');
        for (var i = 0; i < placeholders.length; i++) {
            var placeholder = placeholders[i];
            var name = placeholder.getAttribute('data-biliup-fragment');
            if (!Object.prototype.hasOwnProperty.call(fragments, name)) {
                throw new Error('模块模板片段不存在: ' + name);
            }
            var fragmentTemplate = document.createElement('template');
            fragmentTemplate.innerHTML = fragments[name];
            placeholder.parentNode.replaceChild(fragmentTemplate.content.cloneNode(true), placeholder);
        }
        return container.innerHTML;
    }

    function loadStyles(paths, pageStyle) {
        return Promise.all((paths || []).map(function (path) {
            return loadStyle(path, pageStyle);
        }));
    }

    function activatePageStyles(styleNodes) {
        var activeNodes = styleNodes || [];
        var pageStyles = document.querySelectorAll('link[data-biliup-page-style]');
        for (var i = 0; i < pageStyles.length; i++) {
            pageStyles[i].media = activeNodes.indexOf(pageStyles[i]) >= 0 ? 'all' : 'not all';
        }
    }

    function deactivatePageStyles() {
        activatePageStyles([]);
    }

    function checkFrontendVersion(error) {
        if (!window.FrontendCacheRefresh || typeof window.FrontendCacheRefresh.check !== 'function') {
            return Promise.reject(error);
        }
        return Promise.resolve(window.FrontendCacheRefresh.check()).then(function (changed) {
            if (changed) {
                return new Promise(function () {});
            }
            throw error;
        }, function () {
            throw error;
        });
    }

    function loadTextMap(paths) {
        var names = Object.keys(paths || {});
        return Promise.all(names.map(function (name) {
            return fetchText(paths[name]).then(function (content) {
                return { name: name, content: content };
            });
        })).then(function (entries) {
            return entries.reduce(function (result, entry) {
                result[entry.name] = entry.content;
                return result;
            }, {});
        });
    }

    function loadModule(collectionName, moduleName, surface) {
        var currentSurface = surface === 'mobile' ? 'mobile' : 'desktop';
        var moduleKey = collectionName + ':' + moduleName + '@' + currentSurface;
        if (modulePromises[moduleKey]) {
            return modulePromises[moduleKey];
        }
        modulePromises[moduleKey] = loadManifest().then(function (manifest) {
            var collection = manifest[collectionName];
            var config = collection && collection[moduleName];
            if (!config) {
                throw new Error('模块清单中不存在模块: ' + moduleName);
            }
            if (config.mode !== 'module') {
                throw new Error('模块模式不受支持: ' + moduleName);
            }
            var templatePath = config.templates && config.templates[currentSurface];
            assertSafePath(templatePath);
            var templatePromise = fetchText(templatePath);
            var fragmentPromise = loadTextMap(moduleFragments(config, currentSurface));
            var pageStyle = collectionName === 'pages';
            var stylePromise = loadStyles(moduleStyles(config, currentSurface), pageStyle);
            var dependencyPromise = loadSequential(config.scripts || [], loadScript);
            return Promise.all([templatePromise, fragmentPromise, stylePromise, dependencyPromise]).then(function (result) {
                return loadScript(config.entry).then(function () {
                    var context = {
                        template: composeTemplate(result[0], result[1]),
                        fragments: result[1],
                        surface: currentSurface,
                        pageName: pageStyle ? moduleName : undefined,
                        moduleName: moduleName
                    };
                    var componentName = window.BiliupModuleRegistry.create(config.module, config.component, context);
                    return {
                        moduleName: moduleName,
                        componentName: componentName,
                        config: config,
                        styleNodes: result[2]
                    };
                });
            });
        }).catch(function (error) {
            delete modulePromises[moduleKey];
            return checkFrontendVersion(error);
        });
        return modulePromises[moduleKey];
    }

    function loadPage(pageName, surface) {
        return loadModule('pages', pageName, surface).then(function (result) {
            result.pageName = pageName;
            return result;
        });
    }

    function loadShellModule(moduleName, surface) {
        return loadModule('shell', moduleName, surface);
    }

    window.BiliupModuleLoader = {
        loadManifest: loadManifest,
        loadPage: loadPage,
        loadShellModule: loadShellModule,
        activatePageStyles: activatePageStyles,
        deactivatePageStyles: deactivatePageStyles,
        isSafePath: safePath
    };
})(window, document);
