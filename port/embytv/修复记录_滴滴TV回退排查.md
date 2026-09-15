# 滴滴TV 回退排查记录 (2026-08-29)

## 用户反馈 (v1.3.21 全修复版): 默认软解/无法切换内核/进度条问题/界面卡住
## 处理: 保留修复 1+5, 回退修复 2/3/4, 重装 v1.3.21 回退版

### 保留
1. 网速/解码信息 → 真右上角 (activity_player.xml: kernelInfoText Top_toBottomOf speedTextView → Top_toTopOf parent)
5. 进度条隐藏时网速不残留 (PlayerActivity: versionTag 隐藏 + controlsVisible 检查)

### 回退 (git checkout 恢复)
2. switchPlayer skipEmbyStartReport (PlayerViewModel)
3. seekBar setOnTouchListener (PlayerActivity)
4. switchInQueue 仅EXO重挂surface (PlayerViewModel)

## ADB 远程实测 (v1.3.21 回退版, 播放"青年华盛顿")
- ✅ 进程正常, 无 FATAL/ANR
- ✅ MPV 内核播放 3840x1600 正常
- ✅ **Amlogic HEVC 硬解码器活动** (c2.amlogic.hevc.decoder) → 硬解实际正常
- ⚠️ "默认软解"疑似解码徽章显示问题 (decodeMode 报告) 而非实际软解
- ❌ 无法完成内核切换/进度条/卡界面复现 (盒子 WiFi ADB 中断: Connection refused)

## 待办 (盒子恢复后)
- [ ] 复测: 内核切换 / 进度条点击 / 界面卡住
- [ ] 若仍失败: 抓 logcat 定位 (adb logcat 挂起时盒子 adbd 退出是已知问题)
