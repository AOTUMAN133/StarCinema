# SenPlayer (iOS 6.1.6) 反推 TV 端 UI 设计规范 → 滴滴 TV 改造

> 来源：SenPlayer 6.1.6 解锁版（二进制 + Assets.car + Localizable.strings 全量 UI 词汇表）
> + 官网 senmuse.com + App Store 官方 Apple TV 截图 ×2

## 一、设计语言（浅色苹果原生）

### 颜色 Tokens
| Token | 值 | 用途 |
|---|---|---|
| 背景主色 | #FFFFFF / #FEFEFE | 全局底色 |
| 列表项背景 | #F5F7FA（浅灰蓝）| 卡片/列表项 |
| 分区层次 | #F2F2F2 → #E8E8E8 渐变 | 次级背景 |
| Accent（选中/进度）| #55CBFF 亮蓝 / 系统蓝 #007AFF | 按钮/选中/进度条 |
| 成功/在线 | #19CC66 | 状态 |
| 文本主色 | #000000 | 标题 |
| 文本次级 | 黑 60% (#99000000) | 副标题 |
| 分隔线 | #EBEBEB | 分割 |

### 字体
- **PingFangSC（苹方）**——SenPlayer 打包了 PingFangSC-Regular.otf，TV 端用系统苹方
- 层级：大标题（largeTitle 34-48pt）/ 行标题 17-20pt Bold / 正文 15-17pt / 辅助 12-13pt

### 布局模式（可配置）
- 卡片布局：**Grid 网格 / SingleColumn 单列 / 列表** 三档
- 圆角 10-12pt 卡片、无边框、浅阴影（iOS 原生列表卡）
- 大标题风格：largeTitle（大标题滚动收起）/ smallTitle

## 二、首页（MainHome）——Emby 行列表
- 行类型（Emby API 同构）：**继续观看 ContinueWatching / 下一集 NextUp / 最新 Latest / 推荐 Recommend / 轮播 Carousel**
- 轮播开关：carouselDisplay、mediaLibraryCarousel（媒体库轮播）
- 显示开关：displayContinueWatching / displayNextUpWatching / displayMyMedia / showUnplayedItemCount（未看数徽章）
- 配置：showScrollbar / minimizeTabOnScroll / showSearchBar / refreshHomepage

## 三、详情页（Netflix 式）
- 结构：海报/背景 + 标题 + 评分 + 操作按钮（播放/收藏/已看）+ 剧集列表 + **相似作品 SimilarWorks** + 演职员 ShowCast
- 显示开关：showRating / showCast / showCollection / showPlayProgress / showEpisodeIndicator
- 剧集顺序：episodeOrder / seasonOrder、每季 episodesCount

## 四、播放器（KSPlayer）——**暗色沉浸**
- TV 版播放界面是**暗色**（官方截图 tv_shot2 #002020 墨蓝黑）——媒体库浅色、播放暗色
- 控制栏：controlBar（自动隐藏/位置底部或浮动）、进度条 progressBar + **progressBarPreview（进度预览，TV 有专属 footer）**、miniProgressBar
- 播放：skipOP/skipED（跳过片头片尾，可设置时间点）、playFromLastProgress、nextUpWatching、multipleSpeed、videoDirection、aspectRatio（拉伸/裁剪/适应）
- 解码：switchHardDecode/SoftDecode、audioRenderer、hdrSubtitle、frameRateMatching
- 手势（与滴滴 TV 完全一致的设计）：**左竖滑亮度 / 右竖滑音量 / 双击左右快进后退 / 长按倍速 / 横滑进度**

## 五、弹幕系统
- enableDanmaku、danmakuPosition（顶部/底部/浮动）、danmakuSpeed、danmakuFont/Size、danmakuKeywordFilter（关键词屏蔽）、customDanmakuAPI、danmakuSource
- 弹幕搜索：searchDanmaku + 历史

## 六、字幕系统
- **双字幕 primarySubtitle/secondarySubtitle**、subtitleSearch（在线搜索）、subtitleSource、embedSubtitles、subtitleScale、subtitleVerticalOffset、subtitleAntiOcclusion（字幕防遮挡）、繁简转换

## 七、TV 专属配置（重要——SenPlayer TV 版有专门 UI 设置）
- includeAppleTv / includeIPhoneiPad / includeMac（多端显示范围）
- progressBarPreviewFooterTV（进度条预览 TV 说明）
- tvMainCombEmptyTips / tvResourceEmptyTips（TV 空状态）
- tvGestureTips（TV 手势提示）
- appleTvPro（Apple TV Pro 功能位）
- Siri 控制（"修复-无法使用Siri切下一集(TV)"）

## 八、设置页结构
- 分组：通用 general / 播放 playback / 弹幕 danmaku / 字幕 subtitle / 手势 gesture / 界面 interface / 媒体库 mediaLibrary / 列表 list / 视频 video / 音频 audio
- 主题：themeColor（**主题色选择器**）+ dark/light（深浅色）+ backgroundColor + colorScheme + pageStyle + displayArea
- 其他：服务器管理（Emby/Jellyfin/Plex/115/阿里/百度/123/WebDAV/SMB/FTP/IPTV）、多线路、同步（iCloud/Trakt）、安全密码、问题反馈（QQ群/邮箱/小红书）

## 九、→ 滴滴 TV 改造方案（浅色 SenPlayer 风）

### P0 主题浅色化（核心视觉翻新）
1. **colors.xml/themes.xml 新增浅色主题**（白底 #FFFFFF、卡 #F5F7FA、accent #55CBFF、文本黑）
2. 首页 EmbyFragment：背景浅色 + 左侧渐变改浅色蒙层（或去掉）+ 行标题黑色 30sp + 卡片白底圆角 12dp + 进度条亮蓝
3. 详情页 DetailFragment：背景浅色化 + 滚动 blur 保留 + 卡片白底
4. 播放器保持暗色（SenPlayer 也是暗色播放）+ OSD 对齐（70dp 主按钮、14dp 进度条、亮蓝进度）
5. 设置页浅色化

### P1 布局与组件
- 卡片 Grid 布局 + 单列列表切换设置
- 未看数徽章（showUnplayedItemCount）
- 相似作品（Emby Similar API 已有）
- 跳过片头片尾按钮白底黑字胶囊
- 双字幕（滴滴 TV 已有单字幕，加副字幕切换）

### P2 弹幕/字幕细节
- 弹幕位置/速度/字号设置
- 字幕防遮挡、繁简转换
- 主题色选择器（6 色）

## 十、TV 布局反推（官方 Apple TV 截图）
- **媒体库/浏览界面：浅色**（tv_shot1：白底 + 蓝色系内容）
- **播放界面：暗色**（tv_shot2：#002020 墨蓝黑 + 海报）
- TV 焦点：iOS 无焦点系统，TV 用系统焦点（高亮描边）——滴滴 TV 已有 FocusStyleHelper，改浅色下 accent 描边
- TV 导航：tab（首页/搜索/媒体库/设置）+ 每屏大标题

---
**执行基准**：滴滴 TV 1.3.42 已含上一轮深色特效（智能背景/横卡展开/LiquidGlass 边框）——浅色化时保留可用的（横卡展开、3s 背景延迟），重做配色层。
