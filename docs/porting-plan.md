# 星空影院 — 移植清单（自 EmbyTV/滴滴TV）

> 原则：内核/API/工具层整体复制不改；UI 层按设计稿重写；无关功能不移植。
> 旧项目 /root/EmbyTV 保持不动，本清单只做"读取→复制"。

## A. 整体复制（不改一行，直接搬）

### A1. 播放内核 player/kernel/ （核心资产）
| 文件 | 说明 |
|---|---|
| player/kernel/AbstractVideoPlayer.kt | 播放器抽象基类 |
| player/kernel/DeviceTier.kt | 设备分级自适应 |
| player/kernel/PlayerFactory.kt | 内核工厂 |
| player/kernel/ScreenHdrDetector.kt | HDR 检测 |
| player/kernel/VideoLog.kt | 日志 |
| player/kernel/VideoPlayerEventListener.kt | 事件监听 |
| player/kernel/InterVideoTrack.kt | 轨道接口 |
| player/kernel/impl/exo/*.kt (9个) | EXO 内核：ExoCache/ExoVideoPlayer/ExoPlayerFactory/ExoMediaSourceHelper/DolbyVision/DoviGlCompositor/RawYuv/P7StartupLoadControl/PlaybackFailureClassifier |
| player/kernel/impl/mpv/*.kt (2个) | MPV 内核：MpvPlayerFactory/MpvVideoPlayer |
| mpv/MPVLib.kt | JNI 绑定（勿改） |
| player/model/*.kt (9个) | 数据模型：KernelTrackInfo/TrackReport/PlaybackFailure/PlayerConstant/PlayerType/TrackType/VideoKernelInfo/VideoTrackBean |
| player/PlayerViewModel.kt | 播放 ViewModel |
| player/RemoteKeyConfig.kt | 遥控键位 |
| player/surface/*.kt (5个) | 渲染表面：InterSurfaceView/MpvOsdSurfaceView/RenderSurfaceView/RenderTextureView/SurfaceFactory |

### A2. API 层 api/
| 文件 | 说明 |
|---|---|
| api/EmbyClient.kt | **Emby 全接口**（登录/库/详情/播放/记录/收藏/搜索）|
| api/TmdbClient.kt | TMDB（保留，按需）|
| api/AlistClient.kt | AList 网盘（暂缓：新项目先不做网盘，可后补）|

### A3. 工具/基础 app/ + 通用 view/
| 文件 | 说明 |
|---|---|
| app/PreferencesHelper.kt | 偏好设置（服务器/焦点样式/播放器/缓存配置）|
| app/EmbyServerConfig.kt | 服务器配置模型 |
| view/EmbyImageLoader.kt | 图片加载（Emby 鉴权图片）|
| view/FocusStyleHelper.kt | 焦点样式（缩放/金框/阴影）|
| view/FocusStyleNotifier.kt | 焦点样式刷新通知 |
| danmu/ 目录(4个) | 弹幕模块（按需移植，若新项目第一版不做弹幕可缓）|

### A4. 播放页 UI（整体搬，播放页不重写）
| 文件 | 说明 |
|---|---|
| view/PlayerActivity.kt | 播放器 Activity（手势/硬解切换/字幕/控制栏）|
| res/layout/activity_player.xml | 播放器布局 |
| view/TrackSelectionAdapter.kt | 轨道选择 |
| item_player_episode.xml | 选集列表项 |

## B. 重写（UI 层，按设计稿从零）

| 旧文件 | 新设计 |
|---|---|
| MainActivity.kt | 重写：左侧抽屉导航架构（设计稿：首页/电影/电视剧/综艺/动漫/纪录片/演唱会/4K专区/合集）|
| view/EmbyFragment.kt | 重写：首页（Hero 轮播 + 内容行，按首页设计稿）|
| view/LibraryGridFragment.kt | 重写：媒体库页（设计稿：大标题+筛选标签+3×4网格+无角标+纯数字评分）|
| view/DetailFragment.kt | 重写：详情页（按详情页设计稿）|
| view/SearchFragment.kt | 重写：搜索页 |
| view/SettingsFragment.kt + 各设置页 | 重写（去掉网盘/浅色主题，保留服务器/焦点/播放器设置）|
| view/HorizontalAdapters.kt | 重写：海报卡适配器（去角标）|
| view/SidebarAdapter.kt | 重写：抽屉菜单适配器（固定入口）|
| view/MyPageAdapter.kt | 重写/简化（若新架构不用 ViewPager 则删）|

## C. 不移植（旧项目保留）

| 文件 | 原因 |
|---|---|
| api/SmbClient.kt / SmbHttpServer.kt / WebDavClient.kt | 网盘功能，新项目第一版不做 |
| view/Alist*/Smb*/WebDav*/NetBrowserFragment.kt | 网盘 UI |
| app/SmbServerConfig.kt / WebDavServerConfig.kt | 网盘配置 |
| 浅色主题相关（md_theme 等） | 只做深色 |
| view/PersonWorksFragment.kt | 视详情页设计稿而定 |

## D. 依赖/构建移植

| 项 | 说明 |
|---|---|
| build.gradle.kts | 复制基础配置：minSdk24/targetSdk35/multiDex/R8，AAR 依赖（MPV/EXO）从旧项目 libs/ 复制 |
| libs/*.aar | MPV 内核 AAR（约105MB，不入 git，本地构建保留）|
| res/ 资源 | 复制通用 drawable/color/strings（金色主题色/胶囊/渐变遮罩/焦点边框），删除浅色主题相关 |
| AndroidManifest.xml | 新包名 + 权限 + 组件声明 |

## E. 移植顺序建议

1. 骨架：gradle + manifest + 包结构 + 依赖
2. 复制 A 组全部（内核/API/工具）
3. 写播放页壳子（PlayerActivity 整体搬 + 简单入口验证可播放）
4. 重写 UI：抽屉 → 首页 → 媒体库 → 详情 → 搜索 → 设置
5. 逐页对照设计稿精细落地

## F. 包名

- 新包名：com.starcinema（待用户确认）
- 应用名：星空影院
