package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.phone.data.CallerPhotoEntity
import org.fossify.phone.data.DatabaseProvider
import org.fossify.phone.extensions.config
import java.io.File

object DeliveryPhotoHelper {
    private const val MAX_DELIVERY_PHOTOS = 10

    /**
     * Compresses the image, saves it to the DB, enforces limits, and cleans up temp files.
     * @return The path of the final saved image, or null if it failed.
     */
    suspend fun processAndSavePhoto(
        context: Context,
        rawUri: Uri,
        rawPhoneNumber: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            // 1. Prepare final destination
            val finalFile = File(context.filesDir, "delivery_photos/pod_${System.currentTimeMillis()}.webp")
            finalFile.parentFile?.mkdirs()

            // 2. Compress the image (using your existing compression helper)
            val compressedPath = compressDeliveryPhoto(context, rawUri, finalFile)

            // 3. Delete the giant raw file from the temporary "Loading Dock"
            context.contentResolver.delete(rawUri, null, null)

            // 4. Save to Database
            val normalizedNumber = context.config.normalizeCustomSIMNumber(rawPhoneNumber)
            val dao = DatabaseProvider.get(context).callerRecordDao()

            dao.insertPhoto(
                CallerPhotoEntity(
                    phoneNumber = normalizedNumber,
                    imagePath = compressedPath,
                    createdAt = System.currentTimeMillis()
                )
            )

            // 5. FIFO Auto-Cleanup: Keep the DB lean!
            // We keep all favorites, and the most recent non-favorites, up to MAX_DELIVERY_PHOTOS total.
            val allPhotos = dao.getPhotosForNumber(normalizedNumber)
            if (allPhotos.size > MAX_DELIVERY_PHOTOS) {
                val nonFavorites = allPhotos.filter { !it.isFavorite }.sortedByDescending { it.createdAt }
                val favoritesCount = allPhotos.size - nonFavorites.size
                
                // We want to keep at most (MAX_DELIVERY_PHOTOS - favoritesCount) non-favorites
                val nonFavoritesToKeep = MAX_DELIVERY_PHOTOS - favoritesCount
                if (nonFavoritesToKeep >= 0) {
                    val photosToDelete = nonFavorites.drop(nonFavoritesToKeep)
                    photosToDelete.forEach { oldPhoto ->
                        File(oldPhoto.imagePath).delete()
                        dao.deletePhoto(oldPhoto)
                    }
                }
            }

            return@withContext compressedPath

        } catch (e: Exception) {
            // If it fails, attempt to delete the raw camera file so it doesn't waste space
            try {
                context.contentResolver.delete(rawUri, null, null)
            } catch (ignored: Exception) {}

            return@withContext null
        }
    }

    suspend fun toggleFavorite(context: Context, photo: CallerPhotoEntity): Boolean = withContext(Dispatchers.IO) {
        val normalizedNumber = context.config.normalizeCustomSIMNumber(photo.phoneNumber)
        val dao = DatabaseProvider.get(context).callerRecordDao()
        if (!photo.isFavorite) {
            val allPhotos = dao.getPhotosForNumber(normalizedNumber)
            val favoritesCount = allPhotos.count { it.isFavorite }
            if (favoritesCount >= 3) {
                return@withContext false // Limit reached
            }
        }

        dao.updatePhoto(photo.copy(isFavorite = !photo.isFavorite))
        return@withContext true
    }
}
