# EmbyTV 全量 View 框架重写计划

## 一、能保留的代码（不动）

这些层与 UI 框架无关，完全保留：

| 模块 | 文件 | 说明 |
|------|------|------|
| **播放器内核** | AbstractVideoPlayer, ExoVideoPlayer, MpvVideoPlayer, PlayerFactory, 全部 surface, 全部 model | 纯 Java/Kotlin，与 UI 无关 |
| **网络层** | EmbyClient（全部 API 方法） | Retrofit 风格，纯 IO |
| **数据层** | PreferencesHelper, EmbyServerConfig, SmbServerConfig, WebDavServerConfig | SP/JSON 持久化 |
| **逻辑层** | PlayerViewModel, VideoPlayerEventListener | StateFlow 观察，View 可绑定 |
| **工具类** | VideoLog, RemoteKeyConfig, FocusSettingsScreen 的 prefs 逻辑 | 纯工具 |

## 二、需要重写的 UI 层（按页面分）

```
app/src/main/java/com/embytv/
├── MainActivity.kt          → 单 Activity + FragmentContainerView
├── player/
│   ├── EmbyBrowserScreen.kt → 首页 Fragment + 详情页 Fragment + 搜索页 Fragment
│   ├── PlayerScreen.kt      → 播放器 Activity（继承 Activity，全屏，SurfaceView + 控制栏 View）
│   ├── PlayerSettingsScreen → PreferenceFragmentCompat
│   ├── SubtitleSettingsScreen → PreferenceFragmentCompat
│   ├── FocusSettingsScreen  → PreferenceFragmentCompat
│   ├── RemoteSettingsScreen → PreferenceFragmentCompat
│   ├── ServerManageScreen   → Fragment
│   ├── ServerEditScreen     → Fragment
│   ├── EmbySetupScreen      → Fragment
│   ├── SmbScreen            → Fragment
│   ├── WebDavScreen         → Fragment
│   └── EmbyWebScreen        → WebView Fragment
└── ui/
    ├── theme/               → colors.xml, themes.xml
    ├── components/          → 自定义 View 组件（焦点按钮、进度条、Badge 等）
    └── utils/               → tvFocusBorder 等 → View 版 StateListDrawable
```

## 三、焦点系统（TV 最关键的改造）

Compose 的 `tvFocusBorder`/`tvFocusHighlight` → View 的 **StateListDrawable selector**：

```xml
<!-- drawable/btn_focus_border.xml -->
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_focused="true">
        <shape android:shape="rectangle">
            <solid android:color="#00000000" />
            <stroke android:width="2dp" android:color="#FFFFFF" />
            <corners android:radius="8dp" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#00000000" />
            <stroke android:width="0dp" android:color="#00000000" />
            <corners android:radius="8dp" />
        </shape>
    </item>
</selector>
```

每个 View 通过 `android:background="@drawable/btn_focus_border"` 获得焦点边框，无需代码。

## 四、实施顺序（按重要性）

1. **项目骨架** — 建 XML layout 目录、colors.xml/themes.xml、navigation 图、Fragment 基类
2. **首页**（Fragment + RecyclerView）— 媒体库列表、继续观看、各库最新
3. **详情页**（Fragment + NestedScrollView）— 背景、信息、操作按钮、剧集列表
4. **播放器**（独立 Activity）— SurfaceView + 控制栏 View 层
5. **设置页** — PreferenceFragment 统一处理
6. **其他页面** — 搜索、服务器管理、SMB/WebDAV
7. **焦点验证** — 每个页面 DPad 导航完整

## 五、你确认这个方向？如果确认，我从第一步开始

鉴于工作量很大，按页面逐个翻新——每完成一个页面就编译验证，确保增量可用，而不是一次性全部重写。