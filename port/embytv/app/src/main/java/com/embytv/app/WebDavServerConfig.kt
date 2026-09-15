package com.embytv.app

data class WebDavServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 5005,
    val username: String = "",
    val password: String = "",
    val useHttps: Boolean = false,
    val basePath: String = "/"
) {
    fun baseUrl(): String {
        val protocol = if (useHttps) "https" else "http"
        return "$protocol://$host:$port"
    }
}