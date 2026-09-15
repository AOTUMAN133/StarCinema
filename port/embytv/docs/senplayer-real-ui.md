# SenPlayer 真实 UI 深度拆解（核对修正版）

> ⚠️ 此前拆的是 NuvioTV（SenPlayer 的 TVOS 宿主壳）——**深色卡片墙，抄错对象**。
> 本档基于 SenPlayer 本体（com.wuziqi.SenPlayer 6.1.6 解锁版 + 官网 senmuse.com + App Store 6.2.1）重新核对。

## 一、SenPlayer 是什么（官方定位）

- **官网 senmuse.com**：全能视频播放器，网盘/NAS/Emby/Plex/弹幕
- **全平台**：iPhone / iPad / Mac / **Apple TV（tvOS 17+）**——官方多端
- 播放内核：**KSPlayer**（forward/senplayer 同源，杜比正确映射）
- 定位 = Infuse/VidHub 同级播放器（用户社区："可平替 infuse"）

## 二、设计语言（与 NuvioTV 完全相反！）

### 颜色体系（铁证）
| 来源 | 颜色 | 用途 |
|---|---|---|
| 主色 #fefefe **x1522** | 纯白 | **全局底色（浅色主题）** |
| 白色系渐变 #fdfdfd→#ececec | 白-浅灰 | 卡片/列表层次 |
| item-bg rgba(245,247,250) | #F5F7FA | **列表项背景（浅灰蓝）** |
| item-inner-bg rgba(85,203,255) | #55CBFF | **亮蓝 accent（选中/进度）** |
| 黑色 #000000 x98 | 黑 | 文字/图标（浅色底深色字）|

**结论：SenPlayer = 苹果原生浅色 UI**（白底 + 亮蓝 accent + 浅灰层次），
不是 NuvioTV 那种深色卡片墙。

### 字体
- 系统字体（SF Pro）为主，无内嵌商业字体（无 Inter/自定义字体 bundle）

## 三、UI 结构（Swift 类型名还原）

### 首页 MainHome（Emby 媒体库首页）
- MainHomeState / MainRouteStore
- 行类型：**Resume（继续观看 x79）/ Continue Watching / NextUp（下一集）/ Latest（最新）/ Trending（热门）/ Popular / Recommend**
- → 与滴滴 TV 首页结构同构（都是 Emby 行列表）！

### 详情页（Netflix 式）
- DetailVisualState（视觉）/ DetailMediaState（媒体）/ DetailEpisodeState（剧集）/
  DetailListState（列表）/ DetailRelatedState（**相关推荐**）/ DetailProviderSearchState（**供应商搜索**）
- 结构 = 视觉头图 + 媒体信息 + 剧集列表 + 相关推荐 + 搜索

### 文件/网盘
- FileListPublish（网盘文件列表）/ GroupPublish / MLibPublish / MLibFavoritesCache / EmbyCache

### 播放器（KSPlayer）
- CustomPlayerViewController + KSOptions2 + PlayerFullScreenViewController
- 手势：IOSGestureHelpView（手势引导）、PinchZoomView（**双指缩放**）

### 弹幕系统
- DanmuManager / DanmuAPI / CustomAPIDanmuInfo / InheritDanmuInfo

### 字幕
- JSScriptSubtitleSearch（**JS 脚本字幕搜索**）/ DriverSubtitleSources / EmbySubtitleSources / LocalCacheSubtitleDataSource

### 服务器管理
- ServerItemConfig / 服务器列表 / 多协议（阿里云盘/百度网盘/123盘/115/WebDAV/SMB/FTP/Dropbox/Google Drive/OneDrive/Emby/Plex/IPTV）

## 四、修正后的滴滴 TV 改造方向

### 之前错在哪
抄了 NuvioTV 的**深色卡片墙 + 全屏背景 + 横卡展开**——那是 NuvioTV 的 UI，不是 SenPlayer。

### SenPlayer 风（正确的抄法）
| 维度 | SenPlayer 风格 | 滴滴 TV 现状 | 改动 |
|---|---|---|---|
| 主题 | **浅色白底** #FEFEFE + 浅灰层次 | 深色 | 新增浅色主题 |
| Accent | **亮蓝 #55CBFF** | 蓝 | 对齐 |
| 列表项 | 浅灰蓝 #F5F7FA 圆角卡片 | 深色卡 | 对齐 |
| 首页 | Emby 行列表（继续观看/下一集/最新/热门） | ✅ 已有同构 | 视觉对齐 |
| 详情页 | Netflix 式（媒体/剧集/相关/搜索） | ✅ 已有 | 浅色化 |
| 弹幕 | ✅ 已有（DanmuManager 同理念） | ✅ 滴滴助手有 | — |
| 播放器 | KSPlayer | mpv/EXO | 内核不动，OSD 浅色化 |

### 关键决策点（需要你拍板）
1. **滴滴 TV 改浅色主题**（SenPlayer/Infuse 风，跟你"极简商务浅色"偏好一致）——大改（所有页面主题色）
2. 还是**保留深色、只抄 SenPlayer 的布局结构**（行/详情/弹幕交互）
3. 还是**明暗双主题**（设置切换，默认浅色）

## 五、TVOS 版 ipa 获取进展
- SenPlayer TV 版 = App Store 6.2.1 的 tvOS 构建（官方 tvOS 17+，无独立 ipa 分发）
- 加密 App Store ipa 无法直接分析代码，但**资源层（Assets.car/图片/UI 素材）明文可拆**
- 本地 6.1.6 解锁版 UIDeviceFamily=[1,2]（iPhone/iPad，不含 TV）——UI 设计语言与 TV 版同源，可作视觉参照