# 星空影院 StarCinema — 进度存档

> 最后更新：2026-09-15（v0.6.1，登录方式定稿：仅账号密码）
> 新会话恢复先读本文件 + README.md + docs/ 下三份计划文档

## 一、项目状态

- **新项目目录**：/root/StarCinema（git 已初始化，全部留痕）
- **旧项目**：/root/EmbyTV（滴滴TV）**保持不动**，只读取移植；工作区快照已提取到 port/embytv/
- **目标**：按设计稿从零重写 UI 层（星空影院），内核/API/工具层整体移植自 EmbyTV

## 二、已完成

### 设计阶段
1. ✅ git 仓库 + 留痕规范（README.md：每次修改必 commit，格式 `[版本] 描述`）
2. ✅ 设计稿原则（README 第四节：版面不完整→以风格为准，按 TV 端特点优化）
3. ✅ **移植清单** docs/porting-plan.md（A 组内核/API/工具复制、B 组 UI 重写、C 组不移植）
4. ✅ **骨架计划** docs/skeleton-plan.md（单 Activity+Fragment、抽屉 9 项映射、M1-M6 里程碑）
5. ✅ **设计稿素材 9 张**已入库 docs/design-assets/
6. ✅ **8 页设计稿全部深度分析完成**（docs/design-*.md 规格，详见各文件）
7. ✅ **EmbyTV 工作区快照提取** port/embytv/（v0.0.9，784 文件）：旧项目 git 停在 v1.3.43 而工作区已 v1.3.66，108 个未提交文件保底；109MB MPV AAR 本地保留不入 git

### 开发阶段
8. ✅ **M1 骨架完成**（v0.1.0）：
   - gradle 工程（AGP 8.7.3 / Kotlin 2.4.0 / Gradle 8.9，minSdk24 targetSdk35 compileSdk36，release 签名，ABI 拆分 arm64+v7a）
   - 新包名 **com.starcinema**（applicationId/namespace），应用名"星空影院"
   - **A 组搬运（42 文件）**：player/ 内核全量（AbstractVideoPlayer/DeviceTier/PlayerFactory/PlayerViewModel + exo 9 内核 + mpv 2 内核 + surface 5 + model 9 + RemoteKeyConfig）、api/EmbyClient + TmdbClient、app/PreferencesHelper + EmbyServerConfig、view/EmbyImageLoader + FocusStyleHelper + FocusStyleNotifier、mpv/MPVLib（JNI 原包保留）、model/VideoType
   - MPV AAR 依赖（libs/，109MB 本地，不入 git）
   - PreferencesHelper 裁剪 WebDAV/SMB/AList 网盘段（C 组不移植）
   - 深色黑金主题 Theme.StarCinema（star_bg/star_gold/star_card/star_text）
9. ✅ **M2 播放页接入完成**（v0.2.0）：
   - PlayerActivity（1631 行）+ TrackSelectionAdapter + danmu 模块 5 文件 + 播放页 6 layout + 全量 drawable/color/raw 资源
   - DanmakuFlameMaster 弹幕依赖
   - ✅ **小米盒子真机验证通过**：测试服务器 28096 拉流，《"大"人物》成功播放（EXO/MPV 内核工作正常），focus 已进 PlayerActivity
10. ✅ **M3 抽屉+首页完成**（v0.3.0）：
   - MainActivity（顶部导航栏 ☰/Logo/首页搜索设置 + 时间WiFi + ViewPager2 首页层 + 子页面容器 + 返回栈处理）
   - EmbyFragment 首页：Hero 大横幅轮播（6s 自动 + 手动翻页 + 指示点，高亮项金色）+ 热门推荐/继续观看内容行（海报+评分+续播角度标）
   - 左侧抽屉：媒体库列表（动态 Emby 库）+ 金色胶囊选中态；左键行内左边缘/☰ 打开
   - VideoTypeRecyclerAdapterDiff + **LibraryGridFragment（M4 前置搬运）**
   - 临时自动连接测试服务器（M5 登录页后替换）；openDetail/搜索/设置暂为 M5 占位
   - ✅ **小米盒子真机验证通过**：首页 Hero 轮播真实数据、《出入平安》《丰臣兄弟》等海报行完整、抽屉展开正常
11. ✅ **M4 媒体库页完成**（v0.4.0）：
   - LibraryGridFragment：大标题 + 筛选标签（全部/最新/高分/年份▾/类型▾）+ **3×4 海报网格**（无角标、评分纯数字金色、卡内标题白）——对齐 design-media-library.md 定稿
   - 修复 GridAdapter 引用已删除角标导致的崩溃（item_grid_card.xml 按定稿已删 unwatchedBadge）
   - ✅ **小米盒子真机验证通过**：动画库 3×4 网格、筛选标签齐全（选中金色胶囊）、标题+评分显示正常
12. ✅ **M5 详情/搜索/设置页接入完成**（v0.5.0，编译通过，真机部分验证）：
   - DetailFragment（详情页，963 行）+ PersonWorksFragment（演员作品页）——详情页真机验证通过：全屏背景+金标题+元数据+简介+立即播放/收藏/更多+IMDb/TMDB/Trakt+主演圆头像+相关推荐
   - SearchFragment + SearchResultAdapter（搜索页）；SettingsFragment + ServerList/ServerEdit/GeneralSettings/PlaySettings/FocusSettings/About 子页
   - 设置页按 C 组裁剪：AList/WebDAV/SMB/账户 4 入口已隐藏（不做假功能）
   - EmbyFragment/LibraryGridFragment 详情跳转改回 DetailFragment；MainActivity 搜索/设置入口恢复真实跳转
   - ⚠️ **遗留问题**：详情页"立即播放"点按未进播放器（CENTER 键在盒子焦点上不触发点击，tap 也未生效，待查；播放内核本身 M2 已验证可播）

**13. ✅ M5 收尾 + 设计稿核对 + LG1 完成（v0.5.0，2026-09-15）**：
   - **详情页跳转问题已解决**：之前 tap (256,395) 落到的是首页 Hero 上的"立即播放"按钮（设计稿不应有！见差异 H1），实际详情页按钮坐标 (92,441)-(236,485) 中心 (164,463)。tap 后正确进入 PlayerActivity（logcat `Displayed com.starcinema/.view.PlayerActivity`）
   - **设计稿 vs 实装差异核对**：docs/design-vs-implementation.md（9 大项 31 子项，P1 必做 2 项 / P2 应做 5 项 / P3 打磨 4 项）
   - **D1 抽屉结构决策撤销**：固定 9 项 → 保留动态 Emby 库列表（不同服务器库数差异大）
   - **LG1 临时自动连接移除 + 隐私数据清理**：EmbyFragment 内 hardcode 的测试服务器地址/账号/密码已全部删除，无服务器时引导用户到 ServerListFragment 添加服务器；**所有连接信息由用户运行时输入，源码零硬编码隐私数据**。小米盒子真机验证通过：空状态显示"我的服务器"+"添加服务器"大黄色卡片（对齐 design-server-login.md）
   - **小技巧**：调试时用 `uiautomator dump` 取真实坐标；盒子屏保 `pm disable-user com.xiaomi.mitv.hyper.screensaver` + `pm disable-user com.mitv.tvhome` 防止抢前台

**14. ✅ M6 打磨完成（v0.6.0，2026-09-15）**：
   - ✅ **P2-③ 首页 Hero 去"立即播放"+ 剧集数红色角标**：fragment_emby.xml 删除 bannerPlayBtn 节点；item_poster_card.xml 删除 unwatchedBadge 节点；EmbyFragment 点击迁移到 bannerArea；HorizontalAdapters/DetailFragment 移除 badge 逻辑
   - ✅ **P2-④ 抽屉 Logo 改金色五角星**：activity_main.xml `ic_logo` → `ic_logo_star`
   - ⏳ **P2-⑤ 搜索页真机验证**：UI 代码完整（SearchFragment/SearchResultAdapter + 热门词/大家都在看/搜索网格），需真服务器输入做端到端
   - ✅ **P3-⑥ 详情页元数据字号 16sp→22sp、⭐→★**：fragment_detail.xml 全行更新；与设计稿"2024 · 135分钟 · ★ 8.9"格式对齐
   - ✅ **P3-⑦ 抽屉选中项渐变 + 发光**：bg_sidebar_focus.xml 已实现左亮右暗渐变 + 三层辉光，无需改动
   - ⏳ **P3-⑧⑨ 剧集/演员作品/搜索/设置页真机验证**：代码完整，需真服务器数据（用户在自己服务器上验收）
   - **编译通过**：app-arm64-v8a-debug.apk + app-armeabi-v7a-debug.apk（各 ~52MB）

**20. ✅ 首页六问题深度修复第二轮（v0.8.1，2026-09-15，用户盒子实测反馈+根因再分析）**：
   - **⑤ 导航栏不出现——根本原因**：topNavBar `layout_height="wrap_content"` + 没有 `app:layout_constraintBottom_toBottomOf` 约束 → ConstraintLayout 计算子 View 时给 nav_menu_btn/nav_search 等子 View 高度 = 0,整个顶部栏塌陷!修复=topNavBar 固定 `layout_height="56dp"`(activity_main.xml)
   - **① 右移跳 Hero**：(⑤) 的直接副作用,导航栏塌陷后 nav_search/nav_settings 不在 view tree,焦点系统按几何搜索只能跳到最近 focusable View=bannerArea。修 ⑤ 同步修 ①
   - **② Hero 海报仍错**：(0.8.0 已修 backdropImageTags 但装包时 v0.7.1 旧 Hero 仍跑)。修复=底层改用 `EmbyImageLoader.load` 完整图 centerCrop 全宽铺满(不再是 160px 拉伸的模糊)
   - **③ 热门推荐/继续观看右边没对齐**——根本原因:paddingStart/End=24 + 不可见 marginEnd=0 → 实际是 item 自身无外边距,RecyclerView itemSpacing 不在末项后插入。修复=Adapter 动态给**首项加 marginStart=24,末项加 marginEnd=24**(HorizontalAdapters.kt onBindViewHolder)
   - **④ 热门推荐"更多"无法聚焦**：(⑤) 导航塌陷导致焦点系统混乱的连锁问题。修复=moreText 显式 `nextFocusLeftId=@id/titleText` + `nextFocusRightId=@id/videoList`,双向焦点链明确;并加聚焦变金色反馈
   - **⑥ 继续观看往下按焦点消失**——根本原因:DpadRecyclerView 1.5.0-beta01 必须 `setSelectedPosition(0)` 才会把焦点委托给 item,否则按方向键焦点可能丢失。修复=VideoTypeRecyclerAdapterDiff.onBindViewHolder 中调用 `rh.videoList.setSelectedPosition(0)`
   - 🔴 根因总结：本轮修复发现 ⑤ 才是系列问题的**核心**,顶部栏塌陷导致整个焦点系统崩溃(①/④ 都被它牵连);只有从根上修约束/层级,焦点系统才能正常工作

**21. ✅ 按完整设计文档重构（v0.9.0，2026-09-15）**：
   - **文档来源**：用户提供完整设计文档 https://4m26jt88ke3b2.aiforce.cloud/app/app_17e5z16d4rc（v1.0），已全文存档 `docs/design-doc-full.md`（色彩/字体/间距/图标/9页界面详解/交互规范/焦点态/动画全收录）
   - **用户拍板：左侧导航改为常驻 18%，不再弹出**（原为抽屉式 200dp 隐藏/弹出）
   - **重构内容**：
     - fragment_emby.xml 重写：根改 ConstraintLayout；`librarySidebar` 常驻 `layout_constraintWidth_percent="0.18"`；`homeContent` 占 82%（`layout_constraintWidth_percent="0.82"` + Start_toEndOf sidebar）；删 drawerScrim/translationX 抽屉动画；Hero 边距按文档改 20dp(40px)
     - activity_main.xml：新增 `sidebarAnchor`(18% 宽占位)约束 topNavBar 只覆盖右侧 82% 内容区
     - EmbyFragment.kt：删 openDrawer/closeDrawer/sidebarExpanded 抽屉逻辑；新增 `focusSidebar()`(焦点移入导航栏首项)/`focusContent()`(焦点回内容区)/`isFocusInSidebar()`;`onGlobalLeftKey` 改为"内容区最左元素(行标题/最左海报)按左 → 焦点移入导航栏";导航栏内按右/返回 → 回内容区
     - MainActivity.kt：返回键/方向键逻辑从"抽屉开关"改为"导航栏焦点进出"
   - 与设计文档其余差异（未完成，见待办）：页面边距 40px、横向列表间距 24px、海报卡圆角 16px、背景色 #0A0A0A、金色 #F5C542、Banner 5s、媒体库 4×3 横版网格、搜索页/媒体库页左侧导航

- ⚠️ 遗留：用户反馈"下移再上移回到最初位置" 待复现验证

## 三、待办（下一步）

- [ ] P2-⑤ + P3-⑧⑨ 真机搜索/剧集/演员/设置页端到端验证（需真服务器；UI 代码完整）
- [ ] M6+ release 签名打包 + GitHub 推送（用户自验后定稿发布）

## 四、关键技术备忘（来自旧项目调试）

- **隐私守则（用户明确要求）**：服务器地址、账号、密码、API Key、access token、user id 等**绝不出现在源码/文档/PROGRESS 中**。所有连接信息由用户运行时通过 SharedPreferences 输入。源码扫描命令：`grep -rEn '192\.168\.1\.|28096|48096|test_token|test-key' app/src/main/java app/src/main/res/values`
- **安装包**：release 签名才能覆盖盒子现有包（debug 包 INSTALL_FAILED_UPDATE_INCOMPATIBLE）
- **构建输出**：APK 名带 ABI 后缀 app-armeabi-v7a-release.apk（之前一直装错 app-debug.apk 导致测旧代码）
- **R8**：release 开启会剥 Log；调试临时 isMinifyEnabled=false
- **盒子**：小米盒子屏保 20s 抢前台（svc power stayon 无效）；ADB 验证要快速连续操作；屏保可 pm disable-user com.xiaomi.mitv.hyper.screensaver
- **Hero 手动切**：MainActivity.dispatchKeyEvent 全局路由左右键给 Hero（焦点不在行内时），flipHero 后 post requestFocus 保持焦点
- **服务器**：测试服务器在盒子实测时由用户在登录页输入（隐私不入文档）；开发服务器用 api_key 方式访问（详见本地调试记录，不落文档）
- **MPV AAR 自带 40 个 .so**（arm64+v7a 的 libmpv/libav*/libc++_shared），jniLibs 目录勿重复添加
- **A 组摘除依赖**：PreferencesHelper 原文件含 WebDAV/SMB/AList 配置段（引 C 组类），M1 已裁剪；EmbyClient 仅依赖 gson/okhttp，无内部类依赖

## 五、设计稿原则（用户强调）

1. 设计稿因版面限制**可能不完整**，但**主题风格没问题**，以风格为准
2. 需根据 **TV 端特点优化**：10英尺UI、遥控器焦点、大字距、安全区
3. 一页一页细致分析清楚，保存原图 + 规格文档