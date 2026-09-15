package com.embytv.app

import android.content.Context
import android.content.SharedPreferences

/** 主题模式 */
enum class ThemeMode {
    LIGHT, DARK, SYSTEM
}

/**
 * 保存 Emby 服务器配置 + 播放器设置
 */
class PreferencesHelper(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("emby_tv", Context.MODE_PRIVATE)

    // ====== Emby 多服务器 ======

    /** 当前激活的服务器 ID */
    var activeEmbyServerId: String
        get() = prefs.getString("emby_active_server_id", "") ?: ""
        set(value) = prefs.edit().putString("emby_active_server_id", value).apply()

    /** 所有已保存的 Emby 服务器列表 */
    var embyServers: List<EmbyServerConfig>
        get() {
            val json = prefs.getString("emby_servers", "") ?: ""
            if (json.isBlank()) return emptyList()
            return try {
                val type = object : com.google.gson.reflect.TypeToken<List<EmbyServerConfig>>() {}.type
                com.google.gson.Gson().fromJson(json, type) ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val json = com.google.gson.Gson().toJson(value)
            prefs.edit().putString("emby_servers", json).apply()
        }

    fun upsertEmbyServer(server: EmbyServerConfig) {
        val list = embyServers.toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server)
        embyServers = list
        if (activeEmbyServerId.isBlank()) activeEmbyServerId = server.id
    }

    fun removeEmbyServer(id: String) {
        val list = embyServers.toMutableList()
        list.removeAll { it.id == id }
        embyServers = list
        if (activeEmbyServerId == id) {
            activeEmbyServerId = list.firstOrNull()?.id ?: ""
        }
    }

    fun moveEmbyServer(id: String, up: Boolean) {
        val list = embyServers.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val target = if (up) idx - 1 else idx + 1
        if (target < 0 || target >= list.size) return
        val item = list.removeAt(idx)
        list.add(target, item)
        embyServers = list
    }

    fun activeEmbyServer(): EmbyServerConfig? {
        val list = embyServers
        if (list.isEmpty()) return null
        return list.firstOrNull { it.id == activeEmbyServerId } ?: list.first()
    }

    fun migrateLegacyEmbyConfig() {
        val legacyUrl = prefs.getString("emby_base_url", "") ?: ""
        val legacyToken = prefs.getString("emby_access_token", "") ?: ""
        if (legacyUrl.isNotBlank() && legacyToken.isNotBlank() && embyServers.isEmpty()) {
            val legacyName = prefs.getString("emby_user_name", "") ?: ""
            upsertEmbyServer(
                EmbyServerConfig(
                    id = "legacy-" + legacyUrl.hashCode().toUInt().toString(16),
                    name = legacyName.ifBlank { "默认服务器" },
                    baseUrl = legacyUrl,
                    accessToken = legacyToken,
                    userId = prefs.getString("emby_user_id", "") ?: "",
                    userName = legacyName
                )
            )
        }
    }

    // ====== 兼容旧字段（迁移动态）======

    /** 兼容旧代码读取，返回当前激活服务器地址 */
    var embyBaseUrl: String
        get() = activeEmbyServer()?.baseUrl ?: (prefs.getString("emby_base_url", "") ?: "")
        set(value) = prefs.edit().putString("emby_base_url", value).apply()

    var embyAccessToken: String
        get() = activeEmbyServer()?.accessToken ?: (prefs.getString("emby_access_token", "") ?: "")
        set(value) = prefs.edit().putString("emby_access_token", value).apply()

    var embyUserId: String
        get() = activeEmbyServer()?.userId ?: (prefs.getString("emby_user_id", "") ?: "")
        set(value) = prefs.edit().putString("emby_user_id", value).apply()

    var embyUserName: String
        get() = activeEmbyServer()?.userName ?: (prefs.getString("emby_user_name", "") ?: "")
        set(value) = prefs.edit().putString("emby_user_name", value).apply()

    /** 用户选择的默认播放内核：exo / mpv */
    var embyPreferredPlayer: String
        get() = prefs.getString("emby_preferred_player", "exo") ?: "exo"
        set(value) = prefs.edit().putString("emby_preferred_player", value).apply()

    /**
     * MPV 解码/渲染配置：auto(设备自适应) / high(高端) / low(低端)
     * auto = DeviceTier 自动判定；high = gpu-next + mediacodec；low = gpu + mediacodec,mediacodec-copy
     */
    var playerRenderConfig: String
        get() = prefs.getString("player_render_config", "auto") ?: "auto"
        set(value) = prefs.edit().putString("player_render_config", value).apply()

    val hasEmbyConfig: Boolean
        get() = embyServers.isNotEmpty()

    fun saveEmbyConfig(baseUrl: String, accessToken: String, userId: String, userName: String) {
        val list = embyServers.toMutableList()
        val existing = list.firstOrNull()
        if (existing != null) {
            val updated = existing.copy(
                baseUrl = baseUrl,
                accessToken = accessToken,
                userId = userId,
                userName = userName
            )
            val idx = list.indexOfFirst { it.id == existing.id }
            list[idx] = updated
            embyServers = list
            activeEmbyServerId = existing.id
        } else {
            val server = EmbyServerConfig(
                id = "server-" + baseUrl.hashCode().toUInt().toString(16),
                name = userName.ifBlank { "Emby" },
                baseUrl = baseUrl,
                accessToken = accessToken,
                userId = userId,
                userName = userName
            )
            upsertEmbyServer(server)
        }
    }

    fun clearEmbyConfig() {
        embyServers = emptyList()
        activeEmbyServerId = ""
    }

    // ====== 主题 ======

    /** 主题模式 */
    var themeMode: ThemeMode
        get() = when (prefs.getString("theme_mode", "system") ?: "system") {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
        set(value) = prefs.edit().putString("theme_mode", value.name.lowercase()).apply()

    /** UI 界面风格（保留兼容，View 框架暂不支持切换） */
    var uiStyle: String
        get() = prefs.getString("ui_style", "quiet_glass") ?: "quiet_glass"
        set(value) = prefs.edit().putString("ui_style", value).apply()

    // ====== 播放器设置 ======

    /** 自动跳片头秒数（0=关闭）：也是片头结束位置 */
    var playerSkipIntroSeconds: Int
        get() = prefs.getInt("player_skip_intro_seconds", 0)
        set(value) = prefs.edit().putInt("player_skip_intro_seconds", value).apply()

    /** 方向键快进/快退步长（秒，5=AfuseKtV 默认 fast_forward_rewind_time） */
    var playerSeekSeconds: Int
        get() = prefs.getInt("player_seek_seconds", 5)
        set(value) = prefs.edit().putInt("player_seek_seconds", value).apply()

    /** 片头开始秒数（设置面板记录用，不参与自动跳转判断） */
    var playerIntroStartSeconds: Int
        get() = prefs.getInt("player_intro_start_seconds", 0)
        set(value) = prefs.edit().putInt("player_intro_start_seconds", value).apply()

    /** 片尾跳过秒数：jump 按钮在片头之后时跳到 结尾-该秒数 （默认 10 分钟防片尾字幕） */
    var playerEndSkipSeconds: Int
        get() = prefs.getInt("player_end_skip_seconds", 600)
        set(value) = prefs.edit().putInt("player_end_skip_seconds", value.coerceIn(0, 3600)).apply()

    /** 播放器缓存大小（MB） */
    var playerCacheSizeMb: Int
        get() = prefs.getInt("player_cache_size_mb", 256)
        set(value) = prefs.edit().putInt("player_cache_size_mb", value.coerceIn(64, 2048)).apply()

    /** 着色器配置：0=无, 1=Anime4K_S, 2=Anime4K_M, 3=Anime4K_L */
    var playerShaderProfile: Int
        get() = prefs.getInt("player_shader_profile", 0)
        set(value) = prefs.edit().putInt("player_shader_profile", value).apply()

    // ====== 通用 ======

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    // ====== WebDAV 服务器 ======

    var webdavServers: List<WebDavServerConfig>
        get() {
            val json = prefs.getString("webdav_servers", "") ?: ""
            if (json.isBlank()) return emptyList()
            return try {
                val type = object : com.google.gson.reflect.TypeToken<List<WebDavServerConfig>>() {}.type
                com.google.gson.Gson().fromJson(json, type) ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val json = com.google.gson.Gson().toJson(value)
            prefs.edit().putString("webdav_servers", json).apply()
        }

    fun upsertWebDavServer(server: WebDavServerConfig) {
        val list = webdavServers.toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server)
        webdavServers = list
    }

    fun removeWebDavServer(id: String) {
        webdavServers = webdavServers.filter { it.id != id }
    }

    // ====== SMB 服务器 ======

    var smbServers: List<SmbServerConfig>
        get() {
            val json = prefs.getString("smb_servers", "") ?: ""
            if (json.isBlank()) return emptyList()
            return try {
                val type = object : com.google.gson.reflect.TypeToken<List<SmbServerConfig>>() {}.type
                com.google.gson.Gson().fromJson(json, type) ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val json = com.google.gson.Gson().toJson(value)
            prefs.edit().putString("smb_servers", json).apply()
        }

    fun upsertSmbServer(server: SmbServerConfig) {
        val list = smbServers.toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server)
        smbServers = list
    }

    fun removeSmbServer(id: String) {
        smbServers = smbServers.filter { it.id != id }
    }

    // ====== AList 网盘 ======

    var alistServers: List<com.embytv.api.AlistConfig>
        get() {
            val json = prefs.getString("alist_servers", "") ?: ""
            if (json.isBlank()) return emptyList()
            return try {
                val type = object : com.google.gson.reflect.TypeToken<List<com.embytv.api.AlistConfig>>() {}.type
                com.google.gson.Gson().fromJson(json, type) ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }
        set(value) {
            val json = com.google.gson.Gson().toJson(value)
            prefs.edit().putString("alist_servers", json).apply()
        }

    fun upsertAlistServer(server: com.embytv.api.AlistConfig) {
        val list = alistServers.toMutableList()
        val idx = list.indexOfFirst { it.id == server.id }
        if (idx >= 0) list[idx] = server else list.add(server)
        alistServers = list
    }

    fun removeAlistServer(id: String) {
        alistServers = alistServers.filter { it.id != id }
    }

    // ====== 字幕设置 ======

    /** 字幕字号（MPV sub-font-size，720p 参考）：45=小 55=中 70=大 90=特大 */
    var subtitleFontSize: Int
        get() = prefs.getInt("subtitle_font_size", 55)
        set(value) = prefs.edit().putInt("subtitle_font_size", value.coerceIn(30, 120)).apply()

    /** 字幕颜色（ARGB hex）：默认白色 */
    var subtitleColor: Int
        get() = prefs.getInt("subtitle_color", 0xFFFFFFFF.toInt())
        set(value) = prefs.edit().putInt("subtitle_color", value).apply()

    fun subtitleColorHex(): String {
        val c = subtitleColor
        return "#%06X".format(c and 0xFFFFFF)
    }

    /** 字幕描边大小（sub-border-size）：0=无描边 */
    var subtitleBorderSize: Int
        get() = prefs.getInt("subtitle_border_size", 3)
        set(value) = prefs.edit().putInt("subtitle_border_size", value.coerceIn(0, 8)).apply()

    /** 字幕垂直位置（sub-pos 0-100，100=底部） */
    var subtitlePosition: Int
        get() = prefs.getInt("subtitle_position", 100)
        set(value) = prefs.edit().putInt("subtitle_position", value.coerceIn(50, 100)).apply()

    // ====== 通用 boolean 设置（播放器直通/帧率匹配等） ======

    fun prefsBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    fun setPrefsBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    fun prefsFloat(key: String, default: Float): Float = prefs.getFloat(key, default)

    fun setPrefsFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    fun prefsString(key: String, default: String = ""): String = prefs.getString(key, default) ?: default

    fun setPrefsString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    // ====== 遥控器按键自定义 ======

    /** 按键映射：key name -> action name */
    var remoteKeyMap: Map<String, String>
        get() {
            val json = prefs.getString("remote_key_map", "") ?: ""
            if (json.isBlank()) return emptyMap()
            return try {
                val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
                com.google.gson.Gson().fromJson(json, type) ?: emptyMap()
            } catch (_: Exception) { emptyMap() }
        }
        set(value) {
            prefs.edit().putString("remote_key_map", com.google.gson.Gson().toJson(value)).apply()
        }

    // ====== TV 焦点指示器配置 ======

    /** 焦点边框宽度（dp）0=无边框 */
    var focusBorderWidth: Int
        get() = prefs.getInt("focus_border_width", 3)
        set(value) = prefs.edit().putInt("focus_border_width", value.coerceIn(0, 8)).apply()

    /** 焦点边框颜色（ARGB hex） */
    var focusBorderColor: Int
        get() = prefs.getInt("focus_border_color", -1) // -1=星光金（默认）
        set(value) = prefs.edit().putInt("focus_border_color", value).apply()

    fun focusBorderColorVal(): Int {
        val c = focusBorderColor
        // 星光影院：默认/旧版白色残留 → 统一星光金（迁移兼容）
        return if (c == -1 || c == 0xFFFFFFFF.toInt() || c == 0x00FFFFFF) 0xFFE8B64C.toInt() else c
    }

    /** 焦点缩放倍率（% 0=不变） */
    var focusScalePercent: Int
        get() = prefs.getInt("focus_scale_percent", 5)
        set(value) = prefs.edit().putInt("focus_scale_percent", value.coerceIn(0, 30)).apply()

    /** 焦点圆角半径（dp） */
    var focusCornerRadius: Int
        get() = prefs.getInt("focus_corner_radius", 8)
        set(value) = prefs.edit().putInt("focus_corner_radius", value.coerceIn(0, 30)).apply()

    /** 隐藏焦点指示（用遥控器时通过 OK 键操作，不显示边框/缩放） */
    var focusHidden: Boolean
        get() = prefs.getBoolean("focus_hidden", false)
        set(value) = prefs.edit().putBoolean("focus_hidden", value).apply()

    /** 详情页标题聚焦：进入详情页时焦点优先落在标题上，而非播放按钮 */
    var focusTitleFirst: Boolean
        get() = prefs.getBoolean("focus_title_first", false)
        set(value) = prefs.edit().putBoolean("focus_title_first", value).apply()

    fun setRemoteKeyAction(key: com.embytv.player.RemoteKeyEvent, action: com.embytv.player.RemoteAction) {
        val map = remoteKeyMap.toMutableMap()
        map[key.name] = action.name
        remoteKeyMap = map
    }

    fun remoteActionFor(key: com.embytv.player.RemoteKeyEvent): com.embytv.player.RemoteAction? {
        val map = remoteKeyMap
        // 未自定义或该键未配置 → fallback 到默认映射
        if (map.isEmpty() || !map.containsKey(key.name)) {
            return com.embytv.player.defaultRemoteKeyMap[key] ?: com.embytv.player.RemoteAction.NONE
        }
        return try {
            com.embytv.player.RemoteAction.valueOf(map[key.name]!!)
        } catch (_: Exception) {
            com.embytv.player.defaultRemoteKeyMap[key] ?: com.embytv.player.RemoteAction.NONE
        }
    }
}