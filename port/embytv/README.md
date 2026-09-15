# 滴滴TV

Android TV 播放器（Emby 客户端），原名 EmbyTV。

## 功能特性

- **播放内核**：EXO + MPV 双内核（MPV 为 fongmi 修改版，支持杜比视界直通），右上角可切换硬解/软解
- **字幕**：ASS 字幕大小归一化（PlayResY 自适应，跨影片视觉大小一致）；PGS 位图字幕统一缩放；外挂 SRT/ASS
- **弹幕**：弹幕大小/速度/行数/透明度可调
- **媒体库**：首页媒体库行按最新入库内容聚合（更新中的剧集新增单集自动排前）；网格页 6 列海报、年份标注、未看剧集数角标、9 种排序
- **TV 优化**：遥控器焦点优化（3 米外清晰）、自动隐藏控制栏、返回键分层
- **播放体验**：播放记录、季选择、章节跳过、手势快进/音量/亮度

## 构建

```bash
./gradlew :app:assembleRelease
```

> 注意：`libs/mpv-android-lib-0.1.12-fongmi.aar`（mpv 内核，约 104MB）因超过 GitHub 单文件 100MB 限制未入库，本地构建需自行放置该文件（可从 FongMi 播放器提取，或用项目内 `app/src/main/jniLibs/` 的 so 替代）。

## 签名

`release.keystore`（alias: embytv）覆盖安装同一签名 APK。

## 版本

当前 v1.3.4（versionCode 224），release 构建已启用 R8 混淆 + 资源收缩。