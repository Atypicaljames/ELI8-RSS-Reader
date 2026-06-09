package com.example.rssreader

// BIT/2025/66882 - Mobile App Development

data class Person(
    val firstName: String = "",
    val lastName: String = "",
    val age: Int = 0,
    val profilePicture: String? = null,
    val isBusinessAccount: Boolean = false,
    val isAdmin: Boolean = false
)
