# 前端结构小本本

这份文档记录当前前端的实际结构和维护约定。它是开发手册，不是迁移计划；目录或运行方式发生变化时，请顺手更新这里

## 当前结论

- 前端继续使用 Vue 2 和 classic script，不引入 Node/Vite 构建链
- 桌面端和移动端共用一套模块运行时，但保留各自的页面模板和布局
- `stats`、`history`、`room`、`user`、`log` 都是按需加载的业务页模块
- 系统设置和通知设置是按需加载的壳层模块
- 业务页切换时销毁并重建，不使用 `keep-alive`
- 已加载的 JS、CSS 和模板结果会在当前浏览器会话中缓存；页面 CSS 切走后停用，资源节点不反复删除
- 系统设置和通知设置首次加载后保持挂载，用来保留尚未保存的表单内容
- 主页面不再使用 iframe，也没有跨帧 `postMessage` 协议

## 总体结构

```text
src/main/resources/static/
├─ index.html                         桌面端壳层
├─ mobile/index.html                  移动端壳层
│
├─ modules/
│  ├─ manifest.json                   页面和壳层模块清单
│  ├─ pages/
│  │  ├─ stats/
│  │  ├─ history/
│  │  ├─ room/
│  │  ├─ user/
│  │  └─ log/
│  └─ shell/
│     ├─ system-settings/
│     └─ notification-settings/
│
├─ js/
│  ├─ api.js                          通用请求封装
│  ├─ api/                            按业务划分的接口层
│  ├─ app/
│  │  ├─ module-registry.js           模块工厂注册器
│  │  ├─ module-loader.js             manifest 与动态资源加载器
│  │  ├─ url-resolver.js              context path 解析
│  │  ├─ page-state-coordinator.js    页面状态汇总
│  │  ├─ page-portal-services.js      Element UI 全局层管理
│  │  ├─ page-bootstrap.js            登录等独立页面的公共启动逻辑
│  │  ├─ mobile-viewport.js           移动端视口和输入焦点处理
│  │  ├─ shell.js                     根 Vue 实例组装
│  │  └─ shell/                       壳层 mixin 和设置逻辑
│  └─ components/
│     ├─ page-host.js                 业务页宿主
│     ├─ shell-module-host.js         设置模块宿主
│     ├─ notification-channel-fields.js
│     └─ diagnostic-export-dialog.js
│
├─ css/
│  ├─ base/                           变量、重置、公共组件和主题覆盖
│  ├─ animations/                     公共过渡和动效
│  ├─ pages/home.css                  壳层首页样式
│  └─ diagnostic-export.css           全局诊断导出样式
│
└─ html/
   ├─ login.html                      登录独立页
   ├─ setup.html                      初始化独立页
   └─ captcha.html                    验证码独立页
```

模块化相关的本地检查在仓库根目录的 `scripts/`：

```text
scripts/
├─ check-mobile-redirect.js
├─ check-module-boundaries.js
├─ check-module-runtime.js
├─ check-page-lifecycles.js
└─ check-shell-mixins.js
```

## 首屏与模块加载流程

桌面端 `index.html` 和移动端 `mobile/index.html` 只加载壳层、公共样式、公共请求能力和模块运行时，不直接加载五个业务页的模板、页面 CSS 或重型依赖

业务页加载流程如下：

1. `navigation-page-runtime.js` 从白名单中确定当前页面
2. `page-host.js` 根据 `page` 和 `surface` 请求模块
3. `module-loader.js` 读取并缓存 `modules/manifest.json`
4. 页面模板、模板分片和 CSS 并行加载
5. manifest 中的 `scripts` 按数组顺序串行加载
6. 所有依赖完成后，最后加载 `entry`
7. 入口通过 `BiliupModuleRegistry.define()` 注册组件工厂
8. 加载器传入模板和运行上下文，注册器创建 Vue 组件
9. CSS 完成加载后才激活页面组件，宿主随后移动键盘焦点

页面快速切换时，宿主使用加载令牌忽略旧请求结果。资源加载失败会清除对应 Promise 缓存，先检查前端版本；版本没有变化时显示错误和重试按钮

动态资源必须满足以下条件：

- 使用 `/` 开头的站内绝对路径
- 不允许协议地址、反斜杠、`//` 开头或 `..` 路径
- 所有请求通过 `url-resolver.js` 适配部署 context path
- 模板、JS 和 CSS 都追加当前 frontend build ID

## Manifest 约定

`modules/manifest.json` 是模块资源的唯一入口。业务页放在 `pages`，设置类模块放在 `shell`。

一个普通业务页的声明大致如下：

```json
{
  "pages": {
    "sample": {
      "mode": "module",
      "module": "page.sample",
      "component": "sample-page",
      "templates": {
        "desktop": "/modules/pages/sample/desktop.html",
        "mobile": "/modules/pages/sample/mobile.html"
      },
      "styles": {
        "common": [
          "/modules/pages/sample/page.css"
        ],
        "desktop": [
          "/modules/pages/sample/desktop.css"
        ],
        "mobile": [
          "/modules/pages/sample/mobile.css"
        ]
      },
      "scripts": [
        "/js/api/sample-api.js",
        "/modules/pages/sample/methods/runtime-methods.js"
      ],
      "entry": "/modules/pages/sample/page.js"
    }
  }
}
```

规则：

- `scripts` 的顺序就是 classic script 的依赖顺序
- API 和方法包放在前面，页面入口必须放在 `entry`
- desktop/mobile 都必须有模板，不要用桌面模板配一堆 `v-if` 假装移动版
- 公共 CSS 只写确实共享的内容，布局差异放到对应 surface CSS
- 大模板可以使用 `fragments`；主模板用 `<template data-biliup-fragment="名称">` 声明插入点
- 新增动态资源后，要同步确认 native-image 资源配置能够覆盖它

## 页面组件契约

页面入口统一注册工厂：

```js
BiliupModuleRegistry.define('page.sample', function (context) {
    return {
        name: 'sample-page',
        template: context.template,
        data: function () {
            return {};
        }
    };
});
```

当前 `context` 包含：

- `template`：已经合成分片的最终模板
- `fragments`：本 surface 加载到的模板分片
- `surface`：`desktop` 或 `mobile`
- `pageName`：业务页名称；壳层模块中为 `undefined`
- `moduleName`：manifest 中的模块名称

业务页通过组件事件和壳层通信：

- `page-ready`：首次数据准备完成，可以解除全局加载状态
- `connection-status(Boolean)`：当前实现中 `true` 表示连接或请求异常，`false` 表示恢复正常
- `page-state(payload)`：上报弹窗、工作区或后台操作状态
- `diagnostic-export({ history })`：请求壳层打开诊断导出

`page-state` 的 `kind` 只允许：

- `modal`：弹窗、抽屉、移动端操作面板
- `workspace`：移动端详情等占用页面工作区的模式
- `operation`：删除、批量操作、本地分P上传等会限制导航的任务

常用载荷：

```js
this.$emit('page-state', {
    kind: 'operation',
    source: 'sample-task',
    active: true,
    message: '正在处理',
    blockingClose: true,
    taskId: taskId,
    percent: percent
});
```

同一种 `kind` 可以有多个 `source`。关闭状态时必须使用与打开时相同的 `source`，状态协调器会在最后一个来源结束后再解除壳层锁定

页面模板还要提供两个通用标记：

```html
<div data-page-scroll-root>
    <h1 data-page-focus-target tabindex="-1">页面标题</h1>
</div>
```

- `data-page-scroll-root` 供回顶、滚动恢复和移动端布局使用
- `data-page-focus-target` 供切页后的键盘焦点管理使用
- 壳层不要再按页面名查询 `.room-container`、`.history-main` 等私有选择器

## 页面状态与全局浮层

`page-state-coordinator.js` 按 `page + kind + source` 保存状态，而不是使用一个全局布尔值。它汇总：

- 页面弹窗是否打开
- 移动端是否进入工作区模式
- 是否有后台操作正在运行
- 当前操作提示和关闭保护
- 移动端输入框是否聚焦

Element UI 的 MessageBox 和 Loading 会挂到 `body`，业务页优先使用：

- `$pageConfirm`
- `$pageAlert`
- `$pagePrompt`
- `$pageMsgbox`
- `$pageLoading`

这些封装会自动追加页面专属类、上报 `modal` 状态，并在组件销毁时清理全局浮层。普通 `el-dialog`、`el-select` 等 append-to-body 内容也要设置页面专属 `custom-class` 或 `popper-class`，不要用裸 `.el-dialog` 做页面级覆盖

## 页面生命周期

普通业务页每次切换都会销毁。新增资源时，请一起处理它的退出路径：

- `setTimeout`、`setInterval` 和轮询任务
- `window`、`document`、媒体查询等监听器
- WebSocket 和订阅回调
- ECharts、ArtPlayer、mpegts 等实例
- 对象 URL、临时上传会话和可取消请求
- Element UI portal、遮罩和页面专属 body class
- 所有仍为 active 的 `page-state` 来源

页面入口的 `beforeDestroy` 负责组织清理，具体资源由创建它的方法模块负责关闭。不要依赖下次进入页面时覆盖旧引用，那样看起来能用，后台资源其实还在跑

## 五个业务页

### Stats

路径：`modules/pages/stats/`

- `desktop.html`、`mobile.html`：两套页面模板
- `page.js`：组件状态、computed、watch 和生命周期组装
- `methods/runtime-methods.js`：数据加载、轮询和运行状态
- `methods/chart-methods.js`：ECharts 初始化、更新、resize 和销毁
- `methods/xml-methods.js`：XML 修复流程
- `methods/maintenance-methods.js`：维护和清理任务
- `methods/format-methods.js`：格式化及连接状态处理

ECharts 只在首次进入统计页时加载。新增图表必须同时补齐更新、resize 和 dispose

### History

路径：`modules/pages/history/`

History 是目前拆分最细的页面：

- `desktop.html`、`mobile.html`：列表和页面骨架
- `fragments/`：桌面/移动详情与对话框分片
- `options/`：`state`、`computed`、`watchers`
- `methods/`：批量、详情、归档、审核、弹幕、分P编辑、预览、上传、进度和记录操作
- 多个 CSS 文件按详情、批量、预览、上传状态等职责拆分

本地分P上传、批量任务、播放器和诊断导出都直接使用同窗口服务。修改上传或预览逻辑时，要同时检查取消、切页、刷新、保存失败和临时文件清理路径

### Room

路径：`modules/pages/room/`

- `desktop.html`、`mobile.html`：列表骨架
- `fragments/`：桌面/移动配置和弹窗分片
- `methods/config-methods.js`：配置表单和导入导出
- `methods/deletion-methods.js`：删除任务、恢复和进度轮询
- `methods/media-methods.js`：封面等媒体处理
- `methods/runtime-methods.js`：列表、状态轮询和运行期逻辑
- `methods/ui-methods.js`：页面交互和状态上报

B站投稿分区数据继续使用 `js/data/bili-partitions.js`，不要复制回页面入口。删除任务必须在完成、失败和组件销毁路径中正确解除页面锁

### User

路径：`modules/pages/user/`

用户页体量相对较小，目前由两套模板、公共/端侧 CSS 和一个 `page.js` 组成。接口统一从 `js/api/user-api.js` 调用

### Log

路径：`modules/pages/log/`

- `methods/stream-methods.js`：实时日志连接
- `methods/render-methods.js`：日志格式化和渲染
- `methods/alert-methods.js`：告警与诊断导出
- `methods/ui-methods.js`：筛选、面板和页面状态

销毁时必须释放 WebSocket、滚动/播放器相关监听和弹窗状态

## 壳层职责

`js/app/shell.js` 只组合根 Vue 实例的五个 mixin：

- `navigationPageRuntime`：页面白名单、切页、URL 参数和导航锁
- `connectionReadiness`：连接状态、页面 ready 和断连保留规则
- `viewportScroll`：桌面/移动视口、回顶和焦点处理
- `workspace`：工作区状态与诊断入口
- `updateAlerts`：版本更新和提示

`mixin-guard.js` 会检查 data、methods、computed 等同名冲突。新增壳层 mixin 时，要让模块自己创建和清理 timer、window/document listener，不要把资源清理重新塞回 `shell.js`

系统设置和通知设置通过 `shell-module-host.js` 按需加载：

- `modules/shell/system-settings/`
- `modules/shell/notification-settings/`

通知字段继续复用 `notification-channel-fields.js` 和 `channel-fields.html`，不要在桌面/移动模板各复制一套字段逻辑

## API 层约定

内部接口优先放在 `js/api/`：

- 房间：`room-api.js`
- 用户：`user-api.js`
- 历史：`history-api.js`
- 分P：`part-api.js`
- 预览：`preview-api.js`
- 统计：`stats-api.js`
- 日志：`log-api.js`
- 通知：`notification-api.js`
- 存储：`storage-api.js`
- 诊断：`diagnostic-api.js`

页面方法里尽量调用语义化 API 方法，不要到处直接拼 URL。登录、初始化和验证码仍是独立页面，可以保留各自较轻的依赖边界

## 新增业务页

1. 在 `modules/pages/<page>/` 创建 desktop/mobile 模板、CSS 和 `page.js`
2. 复杂逻辑按职责放到 `methods/`；复杂模板按 surface 放到 `fragments/`
3. 在 `js/api/` 添加需要的语义化接口封装
4. 在 `modules/manifest.json` 声明模板、分片、样式、依赖脚本和入口顺序
5. 在入口中调用 `BiliupModuleRegistry.define('page.<page>', factory)`
6. 为模板补齐 `data-page-scroll-root` 和 `data-page-focus-target`
7. 接入 `page-ready`、连接状态、页面状态和必要的诊断事件
8. 在 `beforeDestroy` 中完成资源清理
9. 把页面名加入壳层白名单、模块页列表和断连规则
10. 更新 manifest/native-image/重定向相关测试，并运行本地检查

如果新增的是需要保留未保存表单的设置模块，放到 manifest 的 `shell` 集合，并使用 `shell-module-host.js`；不要把普通业务页改成常驻

## URL 与部署

- 壳层只接受白名单中的 `?page=`
- 切页使用 `history.replaceState`，不会不断制造浏览器历史记录
- 移动端自动跳转和 `/mobile` 补斜杠会保留查询参数
- 旧 `/html/stats.html`、`history.html`、`room.html` 及移动端地址由控制器重定向到对应模块页
- `url-resolver.js` 负责 `/biliup` 等非根 context path
- `/modules/**` 按公共静态前端资源处理，也适用于启用 Basic Auth 的部署
- `META-INF/native-image/resource-config.json` 必须覆盖 `static/modules/.*`

不要恢复可运行的 iframe 备份页面。旧地址只保留兼容重定向，业务实现以模块页为唯一来源

## 样式约定

- 页面样式以页面根类为边界，例如 `.page-surface-history`
- 不要在页面模块里覆盖裸 `html`、`body`、`.el-dialog` 或其他全局选择器
- append-to-body 内容必须有页面专属 class
- 公共变量和真正跨页面的组件样式放 `css/base/`
- desktop/mobile 的结构差异优先由模板表达，不靠隐藏一整套另一端 DOM
- 不要为了减少文件数重新合并已经按职责拆开的 CSS

页面 CSS 首次加载后会留在文档中，由加载器切换 `media` 激活状态。样式必须能在停用后不影响其他页面

## 本地检查

基础检查：

```powershell
node scripts/check-mobile-redirect.js
node scripts/check-module-boundaries.js
node scripts/check-module-runtime.js
node scripts/check-page-lifecycles.js
node scripts/check-shell-mixins.js
mvn -DskipTests compile
mvn test -DskipITs "-Dtest=MvcConfigTest,HtmlPageControllerRedirectTest,FrontendModuleManifestTest,FrontendVersionServiceTest"
git diff --check
```

浏览器至少覆盖：

- 桌面端 1440px
- 手机端 375px、430px和横屏
- 明暗主题和隐私模式
- 断连与恢复
- 首次加载、缓存命中、404 后重试和快速切页
- 弹窗、工作区、导航锁和浏览器关闭保护
- 页面反复进入退出后的轮询、监听器、WebSocket、播放器和遮罩残留
- 键盘切页焦点、回顶和 `prefers-reduced-motion`

发布前还要做一次打包 JAR 的模块静态资源冒烟；提供 native-image 时，再检查一次原生可执行文件中的模块资源