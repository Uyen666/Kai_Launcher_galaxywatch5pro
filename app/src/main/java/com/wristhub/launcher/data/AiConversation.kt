package com.wristhub.launcher.data

data class AiConversation(
    val id: Long = System.currentTimeMillis(),
    val userText: String,
    val aiReply: String,
    val action: String = "NONE",
    val actionResult: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
