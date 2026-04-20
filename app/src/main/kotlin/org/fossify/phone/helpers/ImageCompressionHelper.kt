package org.fossify.phone.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Takes a massive raw camera photo, reads its correct orientation, scales it down,
 * physically rotates it if necessary (fixes Samsung bug), compresses to WebP, and saves it.
 */
suspend fun compressDeliveryPhoto(context: Context, rawImageUri: Uri, destinationFile: File): String {
    return withContext(Dispatchers.IO) {

        // --- NEW: 1. Read the hidden rotation metadata (EXIF) ---
        var rotationAngle = 0f
        context.contentResolver.openInputStream(rawImageUri)?.use { inputStream ->
            val exif = ExifInterface(inputStream)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            rotationAngle = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }

        // 2. Read the massive raw image
        val imageStream = context.contentResolver.openInputStream(rawImageUri)
        val originalBitmap = BitmapFactory.decodeStream(imageStream)
        imageStream?.close()

        if (originalBitmap == null) {
            throw Exception("Failed to read the captured image.")
        }

        // 3. Scale down to a max dimension of 1080px to save RAM during rotation
        val maxDim = 1080f
        val ratio = Math.min(maxDim / originalBitmap.width, maxDim / originalBitmap.height)
        val width = Math.round(ratio * originalBitmap.width)
        val height = Math.round(ratio * originalBitmap.height)

        val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, width, height, true)

        // --- NEW: 4. Physically rotate the pixels if the phone took it sideways ---
        val finalBitmap = if (rotationAngle != 0f) {
            val matrix = Matrix().apply { postRotate(rotationAngle) }
            val rotatedBitmap = Bitmap.createBitmap(scaledBitmap, 0, 0, scaledBitmap.width, scaledBitmap.height, matrix, true)

            // Clean up the intermediate scaled bitmap
            if (rotatedBitmap != scaledBitmap) {
                scaledBitmap.recycle()
            }
            rotatedBitmap
        } else {
            scaledBitmap
        }

        // 5. Compress directly into our final destination file as WEBP
        val outputStream = FileOutputStream(destinationFile)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            finalBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 70, outputStream)
        } else {
            @Suppress("DEPRECATION")
            finalBitmap.compress(Bitmap.CompressFormat.WEBP, 70, outputStream)
        }

        outputStream.flush()
        outputStream.close()

        // 6. Clean up memory to prevent OutOfMemory crashes
        originalBitmap.recycle()
        finalBitmap.recycle()

        // Return the absolute path so we can save it to your Room Database!
        destinationFile.absolutePath
    }
}
