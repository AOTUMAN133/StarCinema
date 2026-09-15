# 星空影院 StarCinema — 进度存档

> 最后更新：2026-09-15
> 新会话恢复先读本文件 + README.md + docs/ 下三份计划文档

## 一、项目状态

- **新项目目录**：/root/StarCinema（git 已初始化，6 个 commit，全部留痕）
- **旧项目**：/root/EmbyTV（滴滴TV）**保持不动**，只读取移植
- **目标**：按设计稿从零重写 UI 层（星空影院），内核/API/工具层整体移植自 EmbyTV

## 二、已完成

1. ✅ git 仓库 + 留痕规范（README.md：每次修改必 commit，格式 `[版本] 描述`）
2. ✅ 设计稿原则（README 第四节：版面不完整→以风格为准，按 TV 端特点优化）
3. ✅ **移植清单** docs/porting-plan.md（A 组内核/API/工具复制、B 组 UI 重写、C 组不移植）
4. ✅ **骨架计划** docs/skeleton-plan.md（单 Activity+Fragment、抽屉 9 项映射、M1-M6 里程碑）
5. ✅ **设计稿素材 8 张**已入库 docs/design-assets/：
   - 01-APP图标.jpg / 02-首页.jpg / 03-服务器登录页.jpg
   - 04-电影详情页.jpg / 05-剧集详情页.jpg / 06-演员作品.jpg
   - 07-搜索页.jpg / 08-播放界面页.jpg
   - （另有 2 张媒体库页在 .hermes cache：img_1f6bf3d9e103.jpg、img_2ff52aee9810.jpg）
6. ✅ **媒体库页设计规格** docs/design-media-library.md（唯一已完成深度分析的页面）

## 三、待办（下一步）

- [ ] 设计稿逐页深度分析：首页、服务器登录页、电影详情页、剧集详情页、演员作品、搜索页、播放界面页、APP图标（每页 → docs/design-*.md 规格文档）
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
