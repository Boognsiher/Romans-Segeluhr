package com.segeluhr.app.logic

enum class StatusLevel { NORMAL, AMBER, RED, GREEN }

fun interface StatusSink {
    fun setStatus(text: String, level: StatusLevel)
}
