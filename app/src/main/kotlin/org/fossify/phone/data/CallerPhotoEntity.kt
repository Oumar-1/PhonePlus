package org.fossify.phone.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// We index the phoneNumber so looking up a caller's gallery is blazing fast
@Entity(tableName = "caller_photos", indices = [Index("phoneNumber")])
data class CallerPhotoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val phoneNumber: String,
    val imagePath: String,           // The local path to the compressed WebP file
    val description: String = "",    // Optional description
    val isFavorite: Boolean = false, // If true, this photo won't be auto-deleted
    val createdAt: Long = System.currentTimeMillis()
)
