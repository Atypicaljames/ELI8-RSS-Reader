package com.example.rssreader

data class Person(
    val firstName: String = "",
    val lastName: String = "",
    val age: Int = 0,
    val profilePicture: String? = null,
    val isBusinessAccount: Boolean = false,
    val isAdmin: Boolean = false
)
