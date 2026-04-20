package org.fossify.phone.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.fossify.phone.extensions.config

@Entity(tableName = "records")
data class MyRecordEntity(
    @PrimaryKey val phoneNumber: String = "",
    val note: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface MyRecordDao {
    @Upsert
    suspend fun upsert(record: MyRecordEntity)


    @Query("SELECT * FROM records WHERE phoneNumber = :phone LIMIT 1")
    suspend fun getRecord(phone: String): MyRecordEntity?
    // In your DAO interface
    @Query("SELECT * FROM records WHERE phoneNumber = :phoneNumber LIMIT 1")
    fun getRecordSync(phoneNumber: String): MyRecordEntity?

    // get all records
    @Query("SELECT * FROM records")
    suspend fun getAllRecords(): List<MyRecordEntity>

    // get bulk
    @Query("SELECT * FROM records WHERE phoneNumber IN (:phoneNumbers)")
    fun getRecordsByNumbersSync(phoneNumbers: List<String>): List<MyRecordEntity>

    @Query("SELECT COUNT(*) FROM records")
    suspend fun count(): Int


    @Query("SELECT * FROM records ORDER BY createdAt DESC")
    fun recordsFlow(): Flow<List<MyRecordEntity>>
    // Images
    @Insert
    suspend fun insertPhoto(photo: CallerPhotoEntity)

    // Gets all photos for a specific number, newest first
    @Query("SELECT * FROM caller_photos WHERE phoneNumber = :phone ORDER BY createdAt DESC")
    suspend fun getPhotosForNumber(phone: String): List<CallerPhotoEntity>
    // Fetches all photos for the visible numbers on screen, ordered newest to oldest
    @androidx.room.Query("SELECT * FROM caller_photos WHERE phoneNumber IN (:numbers) ORDER BY createdAt DESC")
    suspend fun getPhotosForNumbersSync(numbers: List<String>): List<CallerPhotoEntity>
    // --- Phase 8: Deletion ---
    @androidx.room.Delete
    suspend fun deletePhoto(photo: org.fossify.phone.data.CallerPhotoEntity)

}

@Database(
    entities = [MyRecordEntity::class, CallerPhotoEntity::class],
    version = 2,
    exportSchema = true
)
abstract class MyRecordDatabase : RoomDatabase() {
    abstract fun callerRecordDao(): MyRecordDao

    companion object {
        const val DB_NAME = "caller_records.db"
    }
}

object DatabaseProvider {
    @Volatile
    private var instance: MyRecordDatabase? = null

    fun get(context: Context): MyRecordDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MyRecordDatabase::class.java,
                MyRecordDatabase.DB_NAME
            ).build().also { instance = it }
        }
}

class CallerRecordRepository(
    private val dao: MyRecordDao,
) {

    fun getRecordSync(phoneNumber: String): MyRecordEntity? {
        return dao.getRecordSync(phoneNumber)
    }

    suspend fun addOrUpdateRecord(record: MyRecordEntity) {
        dao.upsert(record)
    }

    suspend fun getRecord(phoneNumber: String): MyRecordEntity? = dao.getRecord(phoneNumber)

    suspend fun getAllRecords(): List<MyRecordEntity> = dao.getAllRecords()
    fun getRecordsByNumbersSync(phoneNumbers: List<String>): List<MyRecordEntity> {
        return dao.getRecordsByNumbersSync(phoneNumbers)
    }

    suspend fun saveNote(context: Context, rawPhoneNumber: String, note: String) {
        // We pass the context in so we can access your config.normalizeCustomSIMNumber extension
        val normalizedPhone = context.config.normalizeCustomSIMNumber(rawPhoneNumber)

        if (note.isNotEmpty() && normalizedPhone.isNotEmpty()) {
            addOrUpdateRecord(
                MyRecordEntity(
                    phoneNumber = normalizedPhone,
                    note = note,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }




}
