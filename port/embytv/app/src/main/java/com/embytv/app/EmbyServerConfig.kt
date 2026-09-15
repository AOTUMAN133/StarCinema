package com.embytv.app

/**
 * Emby 服务器配置
 */
data class EmbyServerConfig(
    val id: String,
    val name: String,
    val baseUrl: String,
    val accessToken: String,
    val userId: String,
    val userName: String,
    /** 服务器类型：emby / jellyfin */
    val serverType: String = "emby"
)