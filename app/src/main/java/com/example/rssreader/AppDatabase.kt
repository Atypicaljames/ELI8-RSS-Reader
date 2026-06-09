package com.example.rssreader

// BIT/2025/66882 - Mobile App Development

import androidx.room.*
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "rss_items")
data class RSSItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val text: String,
    val type: String,
    val media: String?,
    val description: String,
    val url: String,
    val likes: Int,
    val dislikes: Int,
    val comments: String // Stored as JSON string
)

class Converters {
    @TypeConverter
    fun fromString(value: String): List<Comment> {
        val listType = object : TypeToken<List<Comment>>() {}.type
        return Gson().fromJson(value, listType) ?: emptyList()
    }

    @TypeConverter
    fun fromList(list: List<Comment>): String {
        return Gson().toJson(list)
    }
}

@Dao
interface RSSItemDao {
    @Query("SELECT * FROM rss_items")
    fun getAll(): Flow<List<RSSItemEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<RSSItemEntity>)

    @Query("DELETE FROM rss_items")
    suspend fun deleteAll()

    @Transaction
    suspend fun syncItems(items: List<RSSItemEntity>) {
        deleteAll()
        insertAll(items)
    }
}

@Database(entities = [RSSItemEntity::class], version = 2) // Incremented version
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rssItemDao(): RSSItemDao
}
