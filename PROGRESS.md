# StarCinema 星光影院 — 进度档案

> 版本: v0.13.0 (versionCode 17) | 日期: 2026-09-16
> 设计文档: docs/design-doc-full.md v1.0 | 旧壳归档: tag v0.12.1-old-shell

## 一、项目概述

基于 Emby 内核的 Android TV 端媒体播放器，暗金主题设计语言。从零按设计文档 v1.0 重建 UI 层，仅移植播放器内核 + Emby API。

## 二、完成状态

### 核心架构 ✅
- [x] 纯黑金色彩体系 (colors.xml, 无旧污染)
- [x] 8px 网格间距 (dimens.xml)
- [x] Material3.Dark.NoActionBar 主题
- [x] 单 FragmentContainerView 架构 (MainActivity.kt)
- [x] 真 32 位 jniLibs (10 个 so, 从 EmbyTV 复制覆盖 AAR 伪 64 位)

### 9 页 UI (按设计文档 v1.0)

| # | 页面 | 完成度 | 说明 |
|---|---|---|---|
| 01 | App 图标 | **100%** | 星月主题: 立体金色五角星+双月牙金环+闪光星 |
| 02 | 服务器添加页 | **100%** | 左40%金光卡+右60%列表+版本号 |
| 03 | 首页 | **89%** | 侧栏18%+Tab3+Banner轮播5s+热门6张+继续观看(功能在,需播放记录) |
| 04 | 电影详情页 | **88%** | 品牌Logo+serif金标题+元信息+2按钮+主演4+推荐4+媒体三卡 |
| 05 | 剧集详情页 | **83%** | 同04+分季标签+分集横版卡 |
| 06 | 演员作品页 | **100%** | 圆头像金框+代表作/生日+年表6张倒序+焦点首张 |
| 07 | 搜索页 | **80%** | 胶囊搜索框+热门6标签(常驻选中+火焰)+大家都在看4张 |
| 08 | 媒体库页 | **100%** | 筛选5标签+4×3横版网格+卡内评分(分页点拍板不做) |
| 09 | 播放界面 | **100%** | EXO/MPV双内核+弹幕+控制栏全量按钮+5s隐藏 |

**总完成度: 90%**

### UI 细腻度修复 (3 轮)
1. **设计稿素材核对 9 项**: 侧栏选中态金胶囊/Tab金色下划线/Banner指示点居中/金标签/评分卡内/元信息顺序/推荐卡标题卡内/大家都在看竖版
2. **粗糙感修复 6 项**: Banner圆角裁剪(外包clip容器)/热门跨库轮转/移除elevation+translationZ阴影/图标重画Material实心/选中态黑字/详情页去"更多"+品牌Logo
3. **完成度补齐 5 项**: Logo补月牙/主演限4个/搜索标签常驻选中+火焰/标题改serif+去阴影/Banner去阴影

### 播放器 (移植自 v0.12.1-old-shell)
- [x] PlayerActivity.kt (1631 行)
- [x] EXO + MPV 双内核切换
- [x] 弹幕 5 文件 (DandanPlay)
- [x] play_control_bar.xml + 全量控制按钮
- [x] ASS/PGS 字幕支持
- [x] 48 个资源依赖补齐

## 三、已知限制

| 项 | 原因 | 影响 |
|---|---|---|
| serif 字体回退 | 小米盒子 Android TV 系统无衬线字体族 | 标题显示无衬线(代码正确) |
| 继续观看区空白 | 盒子账号无播放记录 (getResumeItems 返回空) | 功能完整,需先播放产生记录 |
| 大家中在看不足4张 | Emby 库数量少,聚合去重后不够 | 数据驱动,非代码问题 |
| 分页圆点不做 | 用户拍板 B 方案 (滚动浏览效率更高) | 设计文档允许 |

## 四、关键文件

| 文件 | 说明 |
|---|---|
| app/build.gradle.kts | versionCode=17, versionName=0.13.0 |
| app/src/main/res/values/colors.xml | 纯黑金色彩 (无旧污染) |
| app/src/main/res/values/dimens.xml | 8px 网格间距 |
| app/src/main/res/values/themes.xml | Material3.Dark.NoActionBar + HeadingGold |
| app/src/main/java/com/starcinema/MainActivity.kt | 干净单 Fragment 容器 |
| app/src/main/java/com/starcinema/view/EmbyFragment.kt | 首页 (轮播+行+继续观看) |
| app/src/main/java/com/starcinema/view/DetailFragment.kt | 详情页 (695 行) |
| app/src/main/java/com/starcinema/view/PlayerActivity.kt | 移植播放器 |
| app/src/main/java/com/starcinema/api/EmbyClient.kt | Emby API + getPersonDetail |
| app/src/main/jniLibs/armeabi-v7a/ | 10 个真 32 位 .so |

## 五、Git 历史

| commit | 说明 |
|---|---|
| a63f336 | 设计文档完成度补齐: Logo月牙/主演4/标签选中/serif/去阴影 |
| 78a22dd | 细腻度修复: Banner裁剪/跨库轮转/去elevation/图标实心/黑字/去更多 |
| 1f7e303 | UI对比修复(设计稿9项) |
| 454e43d | [0.13.0] 从零重建完成 |
| e03c2cb | rebuild-wip M1-M7 |

## 六、测试验证

- 盒子: 192.168.1.147:5555 (MiTV-AZFU0, armeabi-v7a, debug 签名)
- 首页: 零崩溃, 真实数据渲染 (侧栏+Banner+热门+跨库), 选中态金胶囊常驻
- 详情页: 品牌Logo+金标题+元信息顺序+2按钮+主演+推荐+媒体三卡
- 播放器: EXO 初始化+play+ASS 字幕, 零崩溃
- 整体: 无 elevation 阴影, 图标干净扁平, 文字无阴影
