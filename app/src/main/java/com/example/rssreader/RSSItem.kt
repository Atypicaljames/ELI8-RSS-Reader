package com.example.rssreader

// BIT/2025/66882 - Mobile App Development

data class Comment(
    val id: String = "",
    val text: String = "",
    val userName: String = "Anonymous",
    val likes: Int = 0,
    val dislikes: Int = 0,
    val replies: List<Comment> = emptyList()
)

data class RSSItem(
    val id: String = "",
    val title: String = "",
    val text: String = "",
    val type: RSSType = RSSType.TEXT,
    val media: String? = null,
    val description: String = "",
    val url: String = "https://www.google.com",
    var likes: Int = 0,
    var dislikes: Int = 0,
    val comments: List<Comment> = emptyList()
)

enum class RSSType {
    TEXT,
    VIDEO,
    IMAGE,
    AD
}

enum class ThemePreference {
    LIGHT,
    DARK,
    SYSTEM
}
