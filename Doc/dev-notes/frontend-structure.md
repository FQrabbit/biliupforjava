# 前端结构小本本

写给未来的我：如果哪天我又打开前端代码，别一脸懵地问这堆东西都是什么啊？

## 总体约定

- `html/*.html` 和 `index.html`：主要放页面结构、Vue 模板、脚本加载顺序
- `js/api/*`：所有内部接口尽量放这里，页面里少直接拼 URL
- `js/pages/*`：页面自己的 Vue 逻辑
- `js/pages/history/*`：录制历史页太胖了，已经单独拆成 methods 模块
- `js/app/*`：整站公共启动逻辑、主页面壳层、浏览器提示
- `css/*`：样式按公共基础、页面、动画拆开

## 图标图例

```text
📁 文件夹
🧭 主入口
📄 HTML 页面壳
🧠 页面逻辑
🔌 接口层
🧰 公共工具
🎨 样式
📦 第三方库/旧备份
```

## 当前前端结构

```text
📁 src/main/resources/static/
├─ 🧭 index.html
│  主页面壳。首页、用户页、日志页的模板都在这里，真正的主逻辑在 js/app/shell.js
│
├─ 📁 html/
│  ├─ 📄 login.html
│  │  登录页结构。逻辑在 js/pages/login.js
│  ├─ 📄 setup.html
│  │  首次初始化配置页。逻辑在 js/pages/setup.js
│  ├─ 📄 captcha.html
│  │  极验验证码页。逻辑在 js/pages/captcha.js
│  ├─ 📄 room.html
│  │  房间管理页结构。逻辑在 js/pages/room.js
│  ├─ 📄 history.html
│  │  录制历史页结构。逻辑入口在 js/pages/history.js，methods 拆到了 js/pages/history/
│  └─ 📄 stats.html
│     统计页结构。逻辑在 js/pages/stats.js
│
├─ 📁 js/
│  ├─ 🧰 api.js
│  │  通用请求工具。负责全局鉴权、GET/POST/DELETE、fetchBlob
│  │
│  ├─ 📁 api/
│  │  ├─ 🔌 system-api.js
│  │  │  系统配置、版本、工作区状态
│  │  ├─ 🔌 user-api.js
│  │  │  B站账号登录、刷新、更新、删除
│  │  ├─ 🔌 room-api.js
│  │  │  房间列表、新增、编辑、删除、导出配置、线路测速
│  │  ├─ 🔌 history-api.js
│  │  │  录制历史列表、状态刷新、删除、投稿动作、分P编辑任务
│  │  ├─ 🔌 part-api.js
│  │  │  分P列表、文件绑定、暂停/恢复、重扫
│  │  ├─ 🔌 preview-api.js
│  │  │  分P预览、封装任务、取消封装
│  │  ├─ 🔌 stats-api.js
│  │  │  统计维护、回填、清理、XML 修复
│  │  ├─ 🔌 log-api.js
│  │  │  日志历史、告警、清空告警
│  │  ├─ 🔌 captcha-api.js
│  │  │  验证码状态和提交
│  │  └─ 🔌 setup-api.js
│  │     初始化配置读取和提交
│  │
│  ├─ 📁 app/
│  │  ├─ 🧭 shell.js
│  │  │  主页面壳层：导航、主题、iframe、连接状态、缓存刷新、回顶按钮
│  │  ├─ 🧰 page-bootstrap.js
│  │  │  子页面公共启动：主题初始化、前端缓存刷新
│  │  └─ 🧰 browser-warning.js
│  │     旧浏览器提示
│  │
│  ├─ 📁 data/
│  │  └─ 🧾 bili-partitions.js
│  │     B站投稿分区数据。别再塞回 room.js 了，会胖到哭
│  │
│  ├─ 📁 pages/
│  │  ├─ 🧠 user.js
│  │  │  用户管理组件，挂在 index.html 的模板上
│  │  ├─ 🧠 log.js
│  │  │  日志中心组件，WebSocket 日志、历史日志、告警详情都在这里
│  │  ├─ 🧠 login.js
│  │  │  登录页逻辑。注意它不依赖 jQuery，别为了一个请求硬塞 api.js
│  │  ├─ 🧠 setup.js
│  │  │  初始化配置页逻辑
│  │  ├─ 🧠 captcha.js
│  │  │  极验验证码逻辑，和 B站投稿验证码有关
│  │  ├─ 🧠 room.js
│  │  │  房间管理逻辑。房间表单、排序、配置导入导出、线路测速都在这里
│  │  ├─ 🧠 stats.js
│  │  │  统计页逻辑。ECharts 图表、维护任务、清理任务都在这里
│  │  ├─ 🧠 history.js
│  │  │  录制历史页入口。主要放 data、computed、watch、生命周期
│  │  │
│  │  └─ 📁 history/
│  │     ├─ 🧠 common-methods.js
│  │     │  通用工具、页面关闭保护、全局预览消息等
│  │     ├─ 🧠 batch-methods.js
│  │     │  批量选择、批量删除、批量切换可见性
│  │     ├─ 🧠 detail-methods.js
│  │     │  筛选、详情弹窗、分P列表、进度轮询
│  │     ├─ 🧠 edit-parts-methods.js
│  │     │  已发布稿件分P编辑、本地临时上传、保存任务
│  │     ├─ 🧠 preview-methods.js
│  │     │  分P预览、播放器恢复、悬浮播放、MP4 封装任务
│  │     ├─ 🧠 upload-methods.js
│  │     │  上传暂停/恢复、绑定文件、速度和剩余时间计算
│  │     └─ 🧠 record-methods.js
│  │        历史列表操作、状态刷新、发布/重投/删除弹幕等
│  │
│  ├─ 🧰 mixins.js
│  │  Vue 全局混入方法
│  ├─ 🕶️ privacy.js
│  │  隐私模式，负责遮挡文字和图片
│  ├─ 🎨 theme-tokens.js
│  │  主题色、调色板、CSS 变量
│  ├─ 🔁 frontend-cache-refresh.js
│  │  前端资源版本检测和刷新提醒
│  ├─ 🎧 global-preview-player.js
│  │  跨页面悬浮预览播放器
│  ├─ 🧾 version.js
│  │  版本号、更新日志数据
│  ├─ 📦 index.js
│  │  旧入口保留文件，基本不用管
│  └─ 📦 第三方库
│     jquery、vue、element-ui、echarts、ArtPlayer、mpegts 等
│
└─ 📁 css/
   ├─ 🎨 index.css
   │  旧样式入口，继续 import 基础样式
   ├─ 🎨 login.css
   │  登录/初始化相关样式
   ├─ 🎨 room.css
   │  房间管理页样式
   ├─ 🎨 history.css
   │  录制历史页样式。它也很大，别无脑往最后追加
   ├─ 🎨 stats.css
   │  统计页样式
   ├─ 🎨 log.css
   │  日志页样式
   ├─ 🎨 user.css
   │  用户管理页样式
   ├─ 🎨 global-preview-player.css
   │  全局悬浮预览播放器样式
   ├─ 📁 base/
   │  ├─ variables.css
   │  │  全局 CSS 变量
   │  ├─ reset.css
   │  │  全局重置、滚动条基础样式
   │  ├─ element-override.css
   │  │  Element UI 公共覆盖
   │  ├─ shared-components.css
   │  │  公共组件样式
   │  └─ dark-overrides.css
   │     深色主题覆盖
   ├─ 📁 animations/
   │  ├─ transitions.css
   │  │  Vue 过渡、页面切换动画
   │  └─ effects.css
   │     额外视觉动效
   └─ 📁 pages/
      └─ home.css
         主页面首页/导航样式
```

## 下次开发时先看这里

### 新增接口

先去 `js/api/` 找对应模块：

- 房间相关：`room-api.js`
- 历史相关：`history-api.js`
- 分P相关：`part-api.js`
- 预览相关：`preview-api.js`
- 统计相关：`stats-api.js`

页面里尽量不要直接写：

```js
ApiUtil.post('/some/url', ...)
```

更推荐在 API 文件里包一层，然后页面调用语义化方法

### 新增页面逻辑

看页面类型：

- 主壳层行为：`js/app/shell.js`
- 普通独立页面：`js/pages/页面名.js`
- 录制历史页：先看 `js/pages/history/` 下面有没有对应模块

如果是历史页，别一上来就往 `history.js` 塞 methods。`history.js` 现在主要放状态和生命周期，methods 要按职责放进子模块

### 新增样式

优先放页面自己的 CSS：

- 房间页：`css/room.css`
- 历史页：`css/history.css`
- 统计页：`css/stats.css`
- 日志页：`css/log.css`
- 用户页：`css/user.css`

公共样式才放 `css/base/`。不要为了图快写一堆内联 style，不然后面改主题会哭

## 重点踩坑提醒

### `index.html` 是主壳，不是所有页面都塞 iframe

`user` 和 `log` 已经是组件页，直接在主壳里渲染。`room/history/stats` 还是 iframe 子页面

这里很容易手滑：如果以后改导航逻辑，记得看 `shell.js` 的 `componentPages`

### 脚本加载顺序很重要

现在还是 classic script，不是 ES module  也就是说加载顺序就是依赖顺序

比如历史页：

1. 先加载 `api.js`
2. 再加载 `api/history-api.js`、`part-api.js`、`preview-api.js`
3. 再加载 `js/pages/history/*.js`
4. 最后加载 `js/pages/history.js`

顺序错了就会出现 `xxx is not defined`!!!

### 录制历史页是大魔王

历史页以前太大了

- 批量操作：`batch-methods.js`
- 详情和进度：`detail-methods.js`
- 分P编辑：`edit-parts-methods.js`
- 分P预览：`preview-methods.js`
- 上传控制：`upload-methods.js`
- 列表操作：`record-methods.js`

要加功能时先判断属于哪一类，不要把所有东西都塞进 `record-methods.js`，它已经很努力了，别再喂胖它！！

### 批量切换可见性要慢一点

`batch-methods.js` 里批量切换可见性有间隔控制。这个不是磨叽，是为了别把 B站接口打太快

如果以后想改快，先想想风控和失败重试，不要一激动就把等待时间删了(

### 分P预览别忘了销毁播放器

`preview-methods.js` 里同时处理：

- ArtPlayer
- mpegts/flv
- MP4 缓存预览
- 弹幕插件
- 悬浮小窗
- 播放器卡住后的恢复

加预览相关功能时，注意关闭弹窗、切换稿件、切换标签页时要清理播放器和 timer。不然页面看起来没事，后台偷偷占资源，像个不睡觉的小坏蛋

### 分P编辑上传有临时文件清理

`edit-parts-methods.js` 里本地分P上传会用临时 session、chunk upload、取消清理、页面关闭清理

这里不要只看“上传成功”那条路，也要看：

- 取消上传
- 关闭页面
- 保存失败
- 浏览器刷新

不然临时文件可能残留，后面排查起来真的很想抱头蹲下

### `room.js` 不要再塞分区大数据

B站投稿分区已经放到 `js/data/bili-partitions.js`

房间页要用 `window.BILIUPFORJAVA_PARTITIONS`，不要把那坨数据复制回 `room.js`，复制一次，维护人掉一撮头发

### 统计页改图表要注意销毁和 resize

`stats.js` 图表多，ECharts 实例要注意：

- 数据刷新
- tab/筛选切换
- 窗口 resize
- 页面销毁

新增图表时别只管 `init`，也要管后续更新。图表这东西，很占内存

### 登录页不要随便引 jQuery

`login.js` 用 `SystemApi.listConfigWithAuth(token)` 做登录校验，没有依赖 `api.js` 那套 jQuery 请求工具

如果只是加登录页小功能，尽量保持它轻一点


## 常见改动入口速查

```text
改主导航/主题/iframe：
  js/app/shell.js

改房间配置：
  html/room.html
  js/pages/room.js
  js/api/room-api.js
  css/room.css

改录制历史列表：
  html/history.html
  js/pages/history.js
  js/pages/history/record-methods.js
  js/api/history-api.js
  css/history.css

改历史详情/分P进度：
  js/pages/history/detail-methods.js
  js/pages/history/upload-methods.js
  js/api/part-api.js

改分P预览：
  js/pages/history/preview-methods.js
  js/api/preview-api.js
  css/global-preview-player.css
  css/history.css

改统计页：
  html/stats.html
  js/pages/stats.js
  js/api/stats-api.js
  css/stats.css

改登录/初始化：
  html/login.html
  js/pages/login.js
  html/setup.html
  js/pages/setup.js
  css/login.css

加接口：
  js/api/对应模块-api.js
```
~~然后梦到什么再写什么~~