# 星空影院 StarCinema — 进度存档

> 最后更新：2026-09-15（v0.0.8）
> 新会话恢复先读本文件 + README.md + docs/ 下三份计划文档

## 一、项目状态

- **新项目目录**：/root/StarCinema（git 已初始化，全部留痕）
- **旧项目**：/root/EmbyTV（滴滴TV）**保持不动**，只读取移植
- **目标**：按设计稿从零重写 UI 层（星空影院），内核/API/工具层整体移植自 EmbyTV

## 二、已完成

1. ✅ git 仓库 + 留痕规范（README.md：每次修改必 commit，格式 `[版本] 描述`）
2. ✅ 设计稿原则（README 第四节：版面不完整→以风格为准，按 TV 端特点优化）
3. ✅ **移植清单** docs/porting-plan.md（A 组内核/API/工具复制、B 组 UI 重写、C 组不移植）
4. ✅ **骨架计划** docs/skeleton-plan.md（单 Activity+Fragment、抽屉 9 项映射、M1-M6 里程碑）
5. ✅ **设计稿素材 9 张**已入库 docs/design-assets/：图标/首页/登录/电影详情/剧集详情/演员作品/搜索/播放 + 媒体库（媒体库 2 张图在 .hermes cache）
6. ✅ **8 页设计稿全部深度分析完成**（每页 → docs/design-*.md 规格）：
   - design-media-library.md（媒体库页，最早完成）
   - design-app-icon.md（APP 图标：黑金 3D 星形，squircle，无文字）
   - design-home.md（首页：L 型导航 + Hero 横幅轮播 + 内容行）
   - design-server-login.md（服务器登录页：左侧"添加服务器"金卡 + 右侧服务器列表）
   - design-movie-detail.md（电影详情页：沉浸背景 + 左侧信息 + 主演/推荐横排）
   - design-series-detail.md（剧集详情页：分季胶囊 + 分集卡片 + 时长角标）
   - design-actor-works.md（演员作品页：金边头像 + 作品年表横排 + 焦点标题变金）
   - design-search.md（搜索页：大胶囊搜索框 + 热门标签 + 大家都在看）
   - design-player.md（播放界面页：金进度条 + 11 按钮排 + 字幕大小面板）
7. ✅ **EmbyTV 工作区快照提取** port/embytv/（v0.0.9，784 文件）：旧项目 git 最后提交停 v1.3.43 而工作区已 v1.3.66，108 个未提交文件保底；含内核/API/工具/播放页/全部 res 资源/jniLibs .so/构建配置；109MB MPV AAR 本地保留不入 git（.gitignore 已排除 *.aar）

## 三、待办（下一步）

- [ ] 确认新包名（建议 com.starcinema，待用户拍板）
- [ ] M1 骨架：gradle + manifest + 包结构 + 依赖 + 搬运 A 组（内核/API/工具）
- [ ] M2 播放页接入 → M3 抽屉+首页 → M4 媒体库 → M5 详情/搜索/设置 → M6 打磨

## 四、关键技术备忘（来自旧项目调试）

- **安装包**：release 签名才能覆盖盒子现有包（debug 包 INSTALL_FAILED_UPDATE_INCOMPATIBLE）
- **构建输出**：APK 名带 ABI 后缀 app-armeabi-v7a-release.apk（之前一直装错 app-debug.apk 导致测旧代码）
- **R8**：release 开启会剥 Log；调试临时 isMinifyEnabled=false
- **盒子**：小米盒子屏保 20s 抢前台（svc power stayon 无效）；ADB 验证要快速连续操作；屏保可 pm disable-user com.xiaomi.mitv.hyper.screensaver
- **Hero 手动切**：MainActivity.dispatchKeyEvent 全局路由左右键给 Hero（焦点不在行内时），flipHero 后 post requestFocus 保持焦点
- **服务器**：盒子测试服务器 192.168.1.33:28096，账号 533/123321（服务名"馋死你"）；开发 48096 api_key=d916bdc17e6e4443ab72a9441a7a249b
- **旧项目版本**：EmbyTV 当前 1.3.66（媒体库页已按设计稿部分改造：标题32sp独立行+筛选下方+4列网格+无角标+评分纯数字+媒体库页顶栏只留时间WiFi）

## 五、设计稿原则（用户强调）

1. 设计稿因版面限制**可能不完整**，但**主题风格没问题**，以风格为准
2. 需根据 **TV 端特点优化**：10英尺UI、遥控器焦点、大字距、安全区
3. 一页一页细致分析清楚，保存原图 + 规格文档