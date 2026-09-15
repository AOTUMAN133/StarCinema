package com.embytv.player

/** 遥控器可配置的按键事件 */
enum class RemoteKeyEvent(val label: String) {
    MENU_LONG_PRESS("长按菜单键"),
    CENTER_LONG_PRESS("长按OK键"),
    UP_LONG_PRESS("长按方向上"),
    DOWN_LONG_PRESS("长按方向下"),
    LEFT_LONG_PRESS("长按方向左"),
    RIGHT_LONG_PRESS("长按方向右"),
    MEDIA_FAST_FORWARD_LONG("长按快进键"),
    MEDIA_REWIND_LONG("长按快退键"),
}

/** 遥控器按键可执行的动作 */
enum class RemoteAction(val label: String) {
    NONE("无操作"),
    TOGGLE_PLAY_PAUSE("播放/暂停"),
    SEEK_FORWARD_10S("快进 10秒"),
    SEEK_BACKWARD_10S("快退 10秒"),
    SEEK_FORWARD_30S("快进 30秒"),
    SEEK_BACKWARD_30S("快退 30秒"),
    NEXT_CHAPTER("跳转片尾"),
    PREV_CHAPTER("跳转片头"),
    TOGGLE_AUDIO_TRACK("切换音轨"),
    TOGGLE_SUBTITLE("切换字幕"),
    CYCLE_PLAYER_KERNEL("切换播放内核"),
    SPEED_UP("加速"),
    SPEED_DOWN("减速"),
    SPEED_RESET("重置倍速"),
    TOGGLE_LOCK("锁定/解锁"),
    ZOOM_MENU("缩放菜单"),
    TOGGLE_SHADER("切换着色器"),
}

/** 默认按键映射 */
val defaultRemoteKeyMap: Map<RemoteKeyEvent, RemoteAction> = mapOf(
    RemoteKeyEvent.MENU_LONG_PRESS to RemoteAction.TOGGLE_AUDIO_TRACK,
    RemoteKeyEvent.CENTER_LONG_PRESS to RemoteAction.TOGGLE_PLAY_PAUSE,
    RemoteKeyEvent.UP_LONG_PRESS to RemoteAction.SPEED_UP,
    RemoteKeyEvent.DOWN_LONG_PRESS to RemoteAction.SPEED_DOWN,
    RemoteKeyEvent.LEFT_LONG_PRESS to RemoteAction.SEEK_BACKWARD_30S,
    RemoteKeyEvent.RIGHT_LONG_PRESS to RemoteAction.SEEK_FORWARD_30S,
    RemoteKeyEvent.MEDIA_FAST_FORWARD_LONG to RemoteAction.NEXT_CHAPTER,
    RemoteKeyEvent.MEDIA_REWIND_LONG to RemoteAction.PREV_CHAPTER,
)