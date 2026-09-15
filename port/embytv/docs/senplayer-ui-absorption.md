# SenPlayer (NuvioTV) TVOS 完整设计系统全量拆解 → 滴滴 TV 改造映射

> 双源交叉验证：`NuvioTV-SenPlayer.ipa` v0.3.0 build108（二进制逆向，21339 Swift 符号 + Assets.car 3D 卡素材）
> + `bobsupra/NuvioTVOS`（GPL-3.0，175★，Beta 3.3.4，152 Swift 文件全量源码）
> 本档覆盖：架构、设计 tokens、**每一屏**、**每一组件**、动效矩阵、焦点系统、Android 移植映射

---

# 第一部分：App 架构

## 1.1 技术栈
- 纯 SwiftUI（tvOS 26+，向下兼容 tvOS 15.1 有 fallback）
- 播放内核：MPVKit（ffmpeg+libass SoftDec，Metal 渲染，P7→P8.1 DV 转换）／AetherEngine 备选
- 数据：Stremio addon 目录 + TMDB 元数据 + Trakt/Simkl 同步 + Jellyfin/SMB 本地库 + Debrid（RealDebrid/Premiumize/Torbox）
- 跳过片头：IntroDB + AnimeSkip（Kitsu/MAL）服务

## 1.2 导航架构（覆盖层模式）
```
WindowGroup
└─ ContentView
   ├─ .login → LoginView（自动续登录）
   ├─ .profileSelection → UserProfileView（Who's Watching）
   ├─ .main → 5 Tab: Profile/Home/Search/Library/Settings
   └─ overlay 覆盖层: .details / .player / .cloudLibrary / .collectionFolder
                     / .productionBrowse / .personBrowse
```
**关键**：Details/Player 是 **overlay（覆盖在 Tab 之上）**，Tab 树保持挂载
→ 返回时保留精确滚动位置 + 焦点卡片（externalFocus 绑定 + 卡片身份 key）

## 1.3 导航细节
- Tab 图标：person.crop.circle / house / magnifyingglass / rectangle.stack / gearshape
- 转场：easeInOut 0.28s + .transition(.opacity)
- 覆盖层动画：easeInOut 0.18s
- 切换 Profile：全屏封屏 ≥1.8s（固定节拍，读作"app 在做正事"）
- 背景：纯黑 ignoresSafeArea 打底 + ProfileScopedRootBackground（换肤背景）

---

# 第二部分：设计系统 Tokens（全量）

## 2.1 主题色 Accent（6 档，Settings → 换肤）
| 名 | RGB | Hex | 主用途 |
|---|---|---|---|
| White **默认** | 1.0/1.0/1.0 | #FFFFFF | 聚焦边框、高亮、徽章文字 |
| Sky | 0.25/0.62/0.96 | #3F9EF5 | 强调 |
| Emerald | 0.19/0.78/0.48 | #30C77A | 强调 |
| Rose | 0.95/0.31/0.48 | #F24F7A | 强调 |
| Amber | 0.97/0.72/0.26 | #F7B842 | 强调 |
| Violet | 0.60/0.45/0.95 | #9973F2 | 强调 |

Accent 全局用法：聚焦边框 stroke、addon 胶囊徽章、Watchlist 图标、设置项强调、进度元素。

## 2.2 背景（12 档 + AMOLED 开关）
| 名 | RGB | Hex |
|---|---|---|
| Charcoal **默认** | 13/13/13 | #0D0D0D |
| Black (AMOLED) | 0 | #000000 |
| Midnight | 0.020/0.030/0.065 | #050810 |
| Forest | 0.018/0.048/0.036 | #040C09 |
| Plum | 0.045/0.020/0.060 | #0B050F |
| Slate | 0.040/0.046/0.056 | #0A0B0E |
| Wine | 0.110/0.015/0.040 | #1C040A |
| Ocean | 0.012/0.055/0.085 | #030E16 |
| Indigo | 0.035/0.028/0.100 | #09071A |
| Crimson | 0.130/0.012/0.025 | #210306 |
| Rust | 0.100/0.040/0.012 | #1A0A03 |
| Teal | 0.012/0.070/0.065 | #031211 |

AMOLED 开关强制纯黑。

## 2.3 字体系统
- **Inter 字体族**（bundle 内嵌）：Inter-Bold / Inter-SemiBold / Inter-Regular
- 系统 SF 用于 OSD/细节
- 完整字号表：
  | 场景 | 字号/字重 |
  |---|---|
  | Profile 页 "Who's Watching" | Inter-Bold 62 |
  | Profile 副文 | Inter-Regular 28 (60% 白) |
  | 播放器标题 | 38 Bold |
  | 播放器副标题 | 21 Medium (68% 白) |
  | 行标题 | Inter-Bold 30 |
  | 卡片标题 | 18-20（聚焦 .semibold）|
  | addon 徽章 | Inter-SemiBold 16 |
  | Discover 大标题 | 64 Light (40% 白) |
  | Library/Cloud 标题 | 46 Bold |
  | 详情 Logo 区标题 | 48 Bold |
  | 暂停层标题 | 28 Semibold |
  | 播放器时间 | 22 Bold |
  | 搜索框 | 30 |
  | 搜索建议行 | 24 |
  | 跳过按钮 | 16 Bold/SemiBold |
  | 字幕语言胶囊 | 13 Bold |

## 2.4 布局常量（TV 全局）
| 常量 | 值 |
|---|---|
| TVLayout.rowLeading | 48 |
| TVLayout.contentLeading | 150 |
| TVHomeLayout.sectionSpacing | 28 |
| heroBottomPadding | 20 |
| rowsTopPadding | 4 |
| finalRowScrollRunway | 24 |
| stripVerticalPadding | 24 |
| rowTitleBlock | 46 |
| CollectionFolderGridMetrics.posterWidth/Height | 210×315 |
| posterGap | 28 |

## 2.5 卡片系统
- 竖卡 base **210×315**（2:3）；Compact 170×255
- **横卡展开宽 560**，高保持 315/255（**布局占位仍是竖宽，横卡向右溢出 + zIndex 覆盖**）
- 尺寸 6 档：0.85/0.92/1.00/1.06/1.12/1.18
- 圆角 5 档：0/8/**16 默认**/22/28（continuous）
- 聚焦边框：AppFocusOutline.color（= accent 主题色），**宽度 4pt（focusHighlighter 开启 6pt）**——粗描边
- 阴影：black.opacity 柔影（聚焦更深）
- 徽章角半径：base 10 × 档位系数（0/0.6/1/1.3/1.8）

## 2.6 动效矩阵（全量）
| 特效 | 动画 |
|---|---|
| 聚焦卡竖→横 | **spring(response 0.3, dampingFraction 1.0)** 临界阻尼无过冲 |
| 行横向滚动 | easeOut 0.22 |
| 行垂直切换 | easeOut 0.14 |
| 页面转场 | easeInOut 0.28 |
| 覆盖层 | easeInOut 0.18 |
| 预告片淡入 | easeInOut 0.32 |
| 播放器控件 | **spring(0.42, 0.86)** 轻微过冲 |
| OSD 元素 | easeOut 0.16/0.2/0.22（scrub/peek/侧栏）|
| 暂停层 | easeOut 0.22 |
| 加载脉冲 | easeInOut 2.0 repeatForever autoreverse |
| 智能背景切换 | 延迟 3s + fade |
| 快速导航抑制 | suppressFocusAnimations（不排队）|

---

# 第三部分：每一屏深度拆解

## 3.1 Login（登录）
- 中央内容 760 宽，页面 padding 120/80
- 标题 40 Bold 白 + 副文 24 (60% 白) maxWidth 560
- 输入框 26 Semibold
- 成功提示绿 #7DFF9C（0.49/1.0/0.61）

## 3.2 Profile Selection（Who's Watching）
- 顶部 spacer 162 → **Inter-Bold 62 "Who's Watching"** 白
- 14 → Inter-Regular 28 (60% 白)
- 18 → 错误红 20 (90%)
- 头像卡 HStack 18 间距，含 PIN 保护（PIN 点 18×18 白/24%）
- Guest 卡 + 添加 Profile

## 3.3 Home（首页，最复杂）
**三层结构**：
1. **全屏 CrossfadingBackdrop**：焦点卡的 backdrop 全屏铺满
   - CrossfadingBackdrop 实现：当前图保持 → 后台解码新图 → 完成后 fade（防闪白）
   - 快速滚动 `.task(id:)` 取消在途加载（滚动中图像不变）
2. **左侧渐变**（全屏模式）：58% 屏宽，backdropColor 0.96→0.85@22%→0.52@46%→0.14@76%→clear
   - 右上角模式（可配）：65% 宽 × 560 高（Compact 440），水平 mask clear→30%@28%→black@65%，垂直 mask black→black@48%→40%@78%→clear
3. **底部渐变**：40% 屏高，clear→20%@42%→58%@78%→backdropColor@100%

**内容区**（滚动）：
- Hero 顶部（heroEnabled 默认开）：显示当前焦点卡的 TVHeroView（标题/logo/描述/按钮），
  folder 焦点时显示 emoji+标题；**下移到行后 hero 隐藏**
- LazyVStack 行：sectionSpacing 28；focused row ±2 窗口物化卡片（惰性）
- 行 = TVCatalogRow（标题 Inter-Bold 30 + addon 胶囊徽章 + 卡片条）
- 模式：Modern（默认，聚焦横卡）/ Compact / Grid View

**行细节**：卡片 HStack spacing 28（Compact 22），offset 随焦点 index 平移，
滚动动画 easeOut 0.22；行标题 offset(y:8) zIndex 1；条 zIndex 0

## 3.4 Discover（发现）
- 顶部 64 Light 大标题 (40% 白) + 28 Semibold 副题
- LazyVGrid 网格，posterGap 28，页 padding 16/12/28
- 底部 Color.clear 60 高跑道
- 空态：大图标 64 Light + 28 semibold + 22 正文（maxWidth 700）
- FilterMenu：24 semibold 项，高 60

## 3.5 Search（搜索，两种风格可切换）
- **NativeSearchView**：页顶 56 + 搜索框 30，结果 LazyVGrid（posterGap），
  结果行 24 semibold；22 medium 50% 白提示
- **NetflixSearchView**（Netflix 风）：**聚焦建议行 = 黑字白底**（24 Bold 黑 vs 24 regular 85% 白），
  双列建议 LazyVStack 8 间距，columnGap 16
- 热门/历史行 + 清除

## 3.6 Library（媒体库）
- 46 Bold 标题 + 28 semibold (85% 白) 分组标题
- LazyVGrid posterGap 28，页 inset，底部 90
- 分组（movies/series/anime 等）

## 3.7 Cloud Library（云库）
- 30 semibold 70% 白 小标题 + **46 Bold 主标题** + 26 medium 60% 白 副题
- LazyVStack 16 间距文件列表

## 3.8 Details（详情页，5841 行）
**TvDetailsContent**：
- 全屏 TvDetailsBackdrop（backgroundUrl），**滚动下移 → blur 22 + Dimmer**
- 内容 VStack spacing 34：
  1. **TvDetailsLogo**（标题 logo，bottom 10）
  2. **TvDetailsActionRow**（ActionButtons：播放主按钮 56 高 24 padding + 收藏/已看/预告片 20 padding，间距 16/8）
  3. **TvDetailsSummary**（MetadataInfo：48 Bold 标题 + 评分徽章 + 简介）
  4. **TvDetailsEpisodes**（剧集条，top 24；继续观看/下一集标记，进度）
  5. **TvDetailsCastAndTrailer**（CastCrewSection：头像 120×160 r8 + Trailer，top 34）
  6. **More Like This**（相关推荐行，top 40）
  7. **Network / Production 行**（公司）
- 焦点节区驱动滚动：actions→episodes→cast→related（easeOut 滚动 + scrollTo anchor .top）
- 详情内嵌 Stream Picker 全屏覆盖（隔离焦点层级）
- 底部 78 padding 跑道

**ProductionBrowseView / CollectionFolderBrowseView**（公司/文件夹浏览）：
- 头部 minHeight 260（30 medium 70% 白 + bottom 70），滚动渐隐
- 底部渐变：backdropColor 0.95→0.86→0.64→0.34→clear（25%/50%/70%）

## 3.9 Player（播放器，20 文件）
**PlayerControls（OSD）**：
- 顶栏：水平 60 padding + 上 34，标题 38 Bold + 副标题 21 Medium 68% 白，
  阴影 black 82% r18 y6
- 底栏：bottom 54，主按钮 **70×70**（图标 28 semibold），次按钮 70
- 时间轴：总高 44，进度条 14 高（轨道白 14%/28%，播放段白 95%，
  glass 轨道 22%/42%，thumb 白 95% 圆点 12×12 + 阴影），时间 22 Bold
- 容器：Liquid Glass（glassEffect(.regular) + ultraThinMaterial fallback）+ 白 12% 边框

**Liquid Glass 实现**（精确参数）：
- 聚焦：白 16% 填充 + glassEffect(.regular)；fallback ultraThinMaterial + 白 14%
- 未聚焦：白 7% 填充
- 高光边框：LinearGradient 白 55%→20%→35%（topLeading→bottomTrailing 对角），聚焦 1.5pt / 未聚焦 1pt
- 侧栏面板：640 宽，黑 22% + glass + 白 12% 边框 + 阴影 black 40% r28 x-8，标题 34 Bold，padding 28
- 播放器设置面板（SidePanels）：轨道列表/画质/速度

**PlayerView+Overlays**：
- **跳过按钮**：白底 95% **黑字**胶囊（16 Bold/SemiBold，padding 16/8，阴影 black 30% r8 y4）——高对比 CTA
- 暂停层：22 semibold 50% + 28 semibold + 24 (72% 白) + 18 semibold 45%，底部 padding 4/18

**PlayerLoadingOverlay**（电影感）：
- 垂直暗角：black 30%@0→60%@35%→80%@70%→90%@100%
- 径向暗角：black 15%→75%
- 中央：backdrop + logo + 标题，脉冲动画 easeInOut 2.0 repeatForever

**ScrubberViews**（预览条）：
- 预览帧条：白色胶囊 28%/18%，进度 95%，thumb 白 40%/75% 强调，玻璃 22%/42%
- 预览 thumb 阴影 black 45% r4

**PostPlayRecommendationOverlay**（播完推荐）：
- 预告片自动播放 + 底部信息 960 宽（80 左距），渐变 scrim 0.92→0.65→0.18→0.35
- 推荐卡 + "下一集" + 迷你播放器（圆角 16，边框白 100%/35%，阴影聚焦）

**PauseOverlayView**：标题 + 剧集 + 操作

**PlaybackDebugHUD**：680 宽黑 88% r14 + 白 16% 边框 + 阴影 24 y10，等宽 14 日志

**SubtitleOverlay**：字幕（可调字号/颜色/背景），语言胶囊 13 Bold 72% 白黑 36% 底 r8

## 3.10 Settings（设置）
- 左菜单（44/72 padding，56 top） + 内容区（垂直 56）
- 12 背景色板 + 6 accent 色板 + AMOLED 开关
- 卡片圆角 5 档 + 尺寸 6 档选择
- 字幕样式（字号/颜色/背景 hex）+ 预览
- 播放后端选择（MPV/AetherEngine）、缓存、硬件解码选项
- 智能流选择（自动选最佳/手动）、字幕语言优先级

---

# 第四部分：组件库全量

| 组件 | 关键参数 |
|---|---|
| **PosterCard** | 210×315/560 横展开、圆角 16、spring 1.0、预告片 7s、LiquidGlass、accent 边框、观看对勾、进度条、logo 275×84 |
| **LiquidGlassCard** | 白 7%/16% + glass + 对角高光 55/20/35% 1.5pt |
| **ActionButtons** | 主按钮 56 高、24 padding；次按钮 20 padding；间距 16/8 |
| **RatingBadge** | 星 #FFB800 14pt + 评分胶囊（金 20% 底 r8）+ 分级徽章 |
| **MetadataInfo** | 48 Bold 标题 + 年份/时长/分级行 + 简介 |
| **CastCrewSection** | 头像 120×160 r8 + secondary 30% 占位 + 名字 12 |
| **Scrubber** | 轨道 14 高胶囊、thumb 白 95%、预览条 |
| **LoadingPosterCard** | 骨架占位卡（灰 10% 填充闪烁）|
| **TVHeroView** | 大背景 + logo + 标题 + 描述 + 播放按钮 |
| **TVLoadingView** | 全屏加载（旋转 + 文案）|
| **NextEpisodeOverlay** | 21 semibold + 29 bold + 20 medium，圆角=卡片×0.75 |

---

# 第五部分：焦点系统（TV 核心逻辑）

1. **聚焦边框**：accent 色 stroke **4pt**（focusHighlighter 开启 6pt 强调），仅聚焦显示
2. **焦点记忆**：externalFocus 绑定（FocusState<String?>）+ 卡片身份 key（rowID+titleID）
   → 返回精确恢复；"retainFocusAppearance" 覆盖层打开时保留最后选中卡外观
3. **FocusSection 隔离**：行/加载/覆盖层各成焦点节区；覆盖层打开时禁用底层
4. **节区驱动滚动**：详情页 focus 移动自动 scrollTo（actions→episodes→cast→related）
5. **快速导航抑制**：fastNavigation/suppressFocusAnimations 跳过动画不排队
6. **300ms preload**：聚焦稳定后预取横卡图/预告
7. **defaultFocus 恢复**：onAppear 请求初始焦点（延迟主队列）
8. **Menu 安全网**：覆盖层动画间隙的 Menu 按键兜底（防止退 App）

---

# 第六部分：滴滴 TV（EmbyTV）全量改造映射

## 6.1 现状对比
| 维度 | NuvioTV | 滴滴 TV 现状 |
|---|---|---|
| Home 背景 | 全屏智能背景 + 渐变 | 纯色/静态（待确认）|
| 卡片聚焦 | 竖→横 560 展开 + 预告片 | 缩放+蒙层+边框 |
| 毛玻璃 | LiquidGlass 全套 | 无 |
| 换肤 | 12×6 + AMOLED | 主题切换（明暗）|
| 圆角/尺寸 | 5 档 × 6 档设置 | 固定 |
| 详情页 | 滚动 blur + 焦点分区 | 已有基础 |
| OSD | 70dp 主按钮 + 毛玻璃 | 已有（待升级）|
| 跳过按钮 | 白底黑字胶囊 | 已有（风格不同）|
| 行标题 | Inter-Bold 30 + 徽章 | 默认字体 |
| 预告片预览 | 焦点 7s 自动播 | 无 |

## 6.2 P0（视觉冲击，4 周）
1. **全屏智能背景**（FrameLayout 底层 ImageView + 左侧 58% 渐变 LinearGradient drawable + 3s 延迟切换 + 先解码后淡入）
2. **聚焦卡竖→横 560 展开**（横卡溢出覆盖 + zIndex，不动 itemSpacing；ValueAnimator spring 1.0）
3. **预告片预览**（焦点 7s 后卡内 PlayerView 播放 Emby Trailer，失焦释放）
4. **LiquidGlass 卡片**（聚焦白 16% 底 + RenderEffect blur + 对角高光渐变边框 1.5dp）

## 6.3 P1（打磨，2 周）
5. 行标题 30sp Bold + 来源胶囊徽章（accent 18% 底/35% 边）
6. 5 档圆角 + 6 档尺寸设置项
7. 12 背景 × 6 accent 换肤 + AMOLED
8. 详情页滚动 blur 22 + dimmer（RenderEffect）
9. 跳过按钮改白底黑字胶囊（与 OSD 统一）

## 6.4 P2（细节，2 周）
10. spring(0.42, 0.86) OSD 动画
11. OSD 70dp 主按钮 + 14dp 进度条 + 12dp 圆点 + 毛玻璃容器
12. 观看状态右上角对勾徽章
13. 骨架加载行（9 卡占位）
14. 快速导航抑制动画
15. 焦点记忆精确恢复（externalFocus 等价物）
16. Netflix 风搜索建议（黑字白底行）

## 6.5 实现注意（View 框架）
- 全屏背景 Glide 异步 + 渐变遮罩 drawable；横卡溢出需父链 clipChildren=false（已知坑）
- RenderEffect blur 需 API 31+（荣耀 Magic7Pro OK，小米盒需确认/降级）
- 遥控器快速移动抑制动画（对标 fastNavigation）
- 每改必升版本号（build.gradle.kts 三处 + 布局/Toast 硬编码）
- 只做真实功能不做假入口（用户硬性要求）

## 6.6 排期
- W1: 全屏智能背景 + 渐变遮罩
- W2: 聚焦卡横展开 + LiquidGlass
- W3: 预告片预览 + 圆角/尺寸设置
- W4: 换肤系统 + 行标题徽章
- W5: 详情页 blur + OSD 升级
- W6: 徽章/骨架/动画/回归