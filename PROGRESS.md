# 星空影院 StarCinema — 进度存档

> 最后更新：2026-09-15（v0.1.0，M1 完成）
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
   - MainActivity 临时验证入口（M3 重写为抽屉+Fragment）
   - ✅ **编译通过**：app-arm64-v8a-debug.apk + app-armeabi-v7a-debug.apk（各 ~52MB）

## 三、待办（下一步）

- [ ] M2 播放页接入：搬运 PlayerActivity（整体搬）+ TrackSelectionAdapter + 播放页 layout + 简单入口验证可播放
- [ ] M3 抽屉+首页（Hero 轮播 + 内容行）→ M4 媒体库 → M5 详情/搜索/设置 → M6 打磨

## 四、关键技术备忘（来自旧项目调试）

- **安装包**：release 签名才能覆盖盒子现有包（debug 包 INSTALL_FAILED_UPDATE_INCOMPATIBLE）
- **构建输出**：APK 名带 ABI 后缀 app-armeabi-v7a-release.apk（之前一直装错 app-debug.apk 导致测旧代码）
- **R8**：release 开启会剥 Log；调试临时 isMinifyEnabled=false
- **盒子**：小米盒子屏保 20s 抢前台（svc power stayon 无效）；ADB 验证要快速连续操作；屏保可 pm disable-user com.xiaomi.mitv.hyper.screensaver
- **Hero 手动切**：MainActivity.dispatchKeyEvent 全局路由左右键给 Hero（焦点不在行内时），flipHero 后 post requestFocus 保持焦点
- **服务器**：盒子测试服务器 192.168.1.33:28096，账号 533/123321（服务名"馋死你"）；开发 48096 api_key=d916bdc17e6e4443ab72a9441a7a249b
- **MPV AAR 自带 40 个 .so**（arm64+v7a 的 libmpv/libav*/libc++_shared），jniLibs 目录勿重复添加
- **A 组摘除依赖**：PreferencesHelper 原文件含 WebDAV/SMB/AList 配置段（引 C 组类），M1 已裁剪；EmbyClient 仅依赖 gson/okhttp，无内部类依赖

## 五、设计稿原则（用户强调）

1. 设计稿因版面限制**可能不完整**，但**主题风格没问题**，以风格为准
2. 需根据 **TV 端特点优化**：10英尺UI、遥控器焦点、大字距、安全区
3. 一页一页细致分析清楚，保存原图 + 规格文档