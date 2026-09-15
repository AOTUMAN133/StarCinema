package com.embytv.app

data class SmbServerConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 445,
    val username: String = "",
    val password: String = "",
    val domain: String = "",
    val basePath: String = "/"
)