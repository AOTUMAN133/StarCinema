# EmbyTV 1:1 复刻 AfuseKtV — 进度存档

> 更新时间: 2026-09-11
> 当前版本: v1.3.48 (versionCode 268, 已装机小米盒)

## 全新决策(2026-09-11 用户拍板)
- **全量深色复刻**: 用户选择"全量深色复刻", v1.3.43 浅色主题作废
- 规格基准: /root/afusektv_res/AfusektV-全界面UI规格拆解.md (532行)
- 执行计划: /root/.hermes/plans/embytv-afusektv-1to1-replicate.md

## 已完成 ✅

### 1. 主题切深色 (v1.3.47)
- themes.xml: Material3.Light → **Material3.Dark**
- colors.xml: md_theme_* 94 项全部切 AfuseKtV 深色 (背景 #111318 / 文字 #E2E2E9 / 主色 #AAC7FF)
- apple_* 7 色切深色 (apple_background #111318 / card #1D1F26 / text #E2E2E9 / secondary #C4C6D0 / tertiary #8A8A93 / divider #2A2C33 / border #3A3C43) → 20+ 布局自动适配
- 首页遮罩 bg_home_gradient_light/bottom 改深色渐变 (左85%黑 #CC000000 + 底部黑雾)

### 2. 回滚浅色特改 (v1.3.46)
- card_select: 聚焦深色#DA181818+白边; card_select_no: #80111318+圆角18
- 按钮聚焦: #66494949 (未聚焦) / #E3E2E6 (聚焦亮灰, AfuseKtV 原值)
- 屏幕进度条: #FF3A8BFF 蓝
- 抽屉: #CC000000 黑 + 24dp 圆角; 标题恢复白字
- activity_main mask: #80000000 黑纱
- item_video cardBackgroundColor: #80000000

### 3. 海报卡统一 (v1.3.45)
- item_poster_card: 143×188 → **110×165 圆角6**
- item_poster_seeall: 143×188 → 110×165
- item_grid_card: 130×175 → 110×165
- item_detail_card: 107×141 圆角5 → 110×165 圆角6
- item_detail_landscape: 189×102 → **200×120 (1:0.6) 圆角10** (剧集卡)
- item_poster_landscape: 252×135 → 200×108 (1:0.54) 圆角10 (播放记录行)
- item_cast_card: 55×55 圆角28 → **80×80 圆角45 (圆形) elevation 4, 文字14sp**

### 4. 详情页 (v1.3.48)
- 黑纱: #B3000000 → #80000000 (AfuseKtV 50%)
- 标题色: white → ?attr/colorPrimary (浅蓝)
- 操作按钮: 加"重播"(有播放进度时显示 autoplay_24px, restart 从头播)
  - launchPlayer 增加 restart: Boolean = false 参数
- 媒体信息卡确认齐全: 类型/年份/时长 + 分辨率+编码+HDR + 音频轨 + 字幕数 + 容器+大小GB+码率Mbps

## 待办 ⏸️ (用户暂停, 有别的任务)
用户反馈: "媒体信息卡,音频信息卡,视频信息卡呢" —— **详情页媒体信息卡没显示**,
怀疑详情请求 Fields 未带 MediaStreams/MediaSources (EmbyClient.kt 548 行剧集请求有,
但详情页请求在 694-704 行附近, 需确认 getItemDetail/PlaybackInfo 调用链)
→ 下次从排查 EmbyClient 详情接口字段开始

## 文件索引
- 复刻计划: /root/.hermes/plans/embytv-afusektv-1to1-replicate.md
- UI规格文档: /root/afusektv_res/AfusektV-全界面UI规格拆解.md
- 布局分析JSON: /root/afusektv_res/layout_analysis.json
- 深色调色板脚本: /root/EmbyTV/scripts/switch_dark_palette.py
- AfuseKtV 资源: /root/afusektv_res/apktool/

## 关键命令
- 构建: cd /root/EmbyTV && ./gradlew assembleRelease --no-daemon (~4min)
- 装机: adb -s 192.168.1.147:5555 install -r app/build/outputs/apk/release/app-armeabi-v7a-release.apk
- 版本号: build.gradle.kts versionCode/versionName 每改必升