# 星空影院 StarCinema — 开发规范

## 一、Git 留痕规则（强制）

1. **每次修改必须 commit**，无论大小。改完一个功能/修复一个 bug/调一个样式，立即 `git add -A && git commit`
2. **Commit message 格式**：`[版本] 内容描述`
   示例：`[0.1.0] 搭建项目骨架+首页Hero轮播`
3. **版本号规则**：每个可安装版本升 versionCode/versionName，commit 里带上
4. **回退**：出问题用 `git log` 找上一个正常 commit，`git checkout <hash> -- <file>` 或 `git revert <hash>`
5. **分支**：主分支 `main`，大功能可开 `feature/xxx` 分支，完成合并回 main

## 二、编码原则（Karpathy 风格）

1. **先想再写**：不确定就问，有歧义先澄清，有更简单方案先说
2. **简洁优先**：最少代码解决问题，不为未来需求加抽象层
3. **外科手术式修改**：只动必须动的行
4. **改前必读**：改代码前先读完整上下文
5. **每次改完升版本号**（用户极度反感忘升）
6. **验证后再交付**：改完构建+真机验证，不验证不交付

## 三、项目定位

- **名称**：星空影院（StarCinema）
- **包名**：待定（不冲突 com.embytv）
- **内核**：整体移植自 滴滴TV(EmbyTV) 的 exo/mpv 播放内核 + EmbyClient API + 工具层
- **UI**：按设计图稿从零重写，逐页精细落地

## 四、设计稿原则（重要）

1. **设计稿因版面限制可能不完整**：以主题风格为准，细节缺失处按 TV 端特点补全
2. **TV 端特点优化**：10英尺 UI（远距离观看）、遥控器焦点态（金框/放大）、大字号、安全区边距（防 Overscan 裁切）、边缘不逃逸
3. **每页设计稿深度分析后入库**（docs/design-assets/ 原图 + docs/design-*.md 规格）
4. 设计稿优先遵守；设计稿未覆盖处，参考旧项目已定稿的成熟交互（Hero手动切、抽屉开关等）

## 五、移植边界

| 层 | 处理方式 |
|---|---|
| PlayerActivity + MPV/EXO 内核 | 整体复制，不重写 |
| EmbyClient API | 整体复制 |
| EmbyImageLoader / FocusStyleHelper / PreferencesHelper | 整体复制 |
| 弹幕模块 | 按需复制 |
| 首页/分类/详情 UI | 从零重写（按设计稿） |
| 网盘(AList/WebDAV/SMB) | 暂不移植（旧项目保留） |
| 浅色主题 | 不要，只做深色 |
