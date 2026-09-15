# 星光影院 UI 层重写计划（按设计文档 v1.0 严谨构建）

> 2026-09-15 用户拍板：保留内核/API（play验证过），UI 层从零重写，不继承任何历史补丁
> 权威规格：docs/design-doc-full.md（完整设计文档 v1.0，已存档）

## 一、保留/重写边界

**保留（不动，已验证）**：
- player/（双内核播放器 M2 真机验证）
- api/EmbyClient.kt + TmdbClient.kt（Emby API 客户端）
- danmu/（弹幕模块）
- mpv/MPVLib.kt（JNI）
- app/PreferencesHelper.kt + EmbyServerConfig.kt（共享配置）
- model/VideoType.kt
- view/EmbyImageLoader.kt、FocusStyleHelper.kt、FocusStyleNotifier.kt（工具）

**重写（删除重建）**：
- MainActivity.kt（顶部栏 82%、侧栏锚点、ViewPager2、返回栈）
- view/EmbyFragment.kt（首页：常驻18%侧栏+Banner+热门推荐+继续观看）
- view/DetailFragment.kt（电影/剧集详情页二合一）
- view/LibraryGridFragment.kt（媒体库 4×3 网格）
- view/SearchFragment.kt + SearchResultAdapter.kt（搜索页）
- view/PersonWorksFragment.kt（演员作品页）
- view/ServerListFragment.kt + ServerEditFragment.kt（服务器页）
- view/SettingsFragment.kt + 子页（General/Play/Focus/About）
- view/SidebarAdapter.kt（侧栏，常驻）
- view/HorizontalAdapters.kt、VideoTypeRecyclerAdapterDiff.kt（首页行/海报卡）
- 全部 res/layout/*.xml（按文档重建）
- PlayerActivity.kt（内核接线保留，UI 布局按文档 09 重写）

**删除**：所有 SMB/WebDAV/AList/账户 布局（C 组不移植，防误用）

## 二、UI 基座（先建，文档第二章）

1. colors.xml：文档色彩体系（金 #FFE082/#F5C542/#D4A017/#B8860B；背景 #0A0A0A/#141414/#1A1A1A；文字 #FFF/#B0B0B0/#666）
2. dimens.xml：基础网格8 / 页边距40 / 卡片距16-24 / 圆角8-12-16-胶囊
3. themes.xml：深色主题（文档配色）
4. 焦点态 drawable：卡片 2px金框+20px发光+scale1.03 / 按钮金渐变+强发光+上移1px / 导航金胶囊 / 列表金色半透明+左指示条
5. 图标：线性2px描边，导航24/按钮20/辅助16（复用现有 ic_sidebar_* 资源验证风格）

## 三、逐页构建顺序（文档第四章）

1. **首页（重点）**：MainActivity 顶部栏(82%) + EmbyFragment 常驻18%侧栏 + Banner(5s轮播,焦点暂停) + 热门推荐6张竖版 + 继续观看横版进度条
2. **服务器添加页**：左右分栏 40/60, 添加服务器卡 + 服务器列表 + 版本号
3. **媒体库页**：18%侧栏 + 4×3横版网格 + 5个筛选标签 + 分页点
4. **电影详情页**：背景横版海报+左渐变遮罩 + 金标题/元信息/简介/立即播放/收藏 + 相关推荐4张横版
5. **剧集详情页**：+ 主演圆头像4个 + 分季标签 + 分集横版列表
6. **演员作品页**：大圆头像+信息+作品年表倒序
7. **搜索页**：18%侧栏 + 搜索框 + 热门6标签 + 大家都在看4张
8. **播放界面**：控制栏（后退10/暂停大金圆/快进10/上下集/倍速/音轨/字幕/弹幕/内核/字幕大小）+ 进度条金渐变拖拽点 + 5s隐藏 + 字幕大小面板

## 四、交互规范（文档第五章，全局一致）

- 卡片焦点：2px金框+发光+scale1.03（200ms ease-out）
- 按钮焦点：金渐变+强发光+上移1px
- 导航焦点：金渐变胶囊+文字变金+发光
- 列表项：金色半透明+左侧金色指示条
- 页面转场 300ms fade；Banner 500ms；弹窗 250ms
- 距屏边 ≥40px；可交互元素必须有焦点态（禁止无反馈）
- 遥控器：方向键移动/OK确认/返回/Menu上下文菜单/Home回首页

## 五、验证

- 每页完成 → 编译 → commit（版本号递增）
- 首页完成后装盒子实测焦点
- 服务器页需真服务器（用户自测）