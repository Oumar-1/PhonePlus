package org.fossify.phone.dialogs

import android.app.Activity
import android.content.Intent
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.toast
import org.fossify.phone.R
import org.fossify.phone.adapters.DeliveryPhotosAdapter
import org.fossify.phone.data.DatabaseProvider
import org.fossify.phone.extensions.config
import org.fossify.phone.helpers.MAX_IMAGES_PER_CONTACT
import java.io.File

class DeliveryGalleryDialog(
    private val activity: Activity,
    private val phoneNumber: String,
    private val onGalleryEmpty: (() -> Unit)? = null,
    private val onNewestPhotoDeleted: ((String) -> Unit)? = null
) {

    fun show() {
        CoroutineScope(Dispatchers.IO).launch {
            val normalizedNumber = activity.config.normalizeCustomSIMNumber(phoneNumber)
            val dao = DatabaseProvider.get(activity).callerRecordDao()
            val photos = dao.getPhotosForNumber(normalizedNumber).toMutableList()

            withContext(Dispatchers.Main) {
                if (photos.isEmpty()) {
                    activity.toast("No photos found.")
                    return@withContext
                }

                val view = activity.layoutInflater.inflate(R.layout.dialog_delivery_gallery, null)
                val headerText = view.findViewById<TextView>(R.id.gallery_header_title)
                val recyclerView = view.findViewById<RecyclerView>(R.id.gallery_recycler_view)
                val closeButton = view.findViewById<TextView>(R.id.gallery_close_button)

                var photosRemaining = photos.size

                fun updateHeaderText() {
                    headerText.text = "Customer: $phoneNumber\n($photosRemaining/$MAX_IMAGES_PER_CONTACT Photos)"
                }

                updateHeaderText()
                headerText.setTextColor(activity.getProperTextColor())
                recyclerView.layoutManager = LinearLayoutManager(activity)

                val galleryDialog = android.app.AlertDialog.Builder(activity, R.style.Theme_AppCompat_Dialog)
                    .setView(view)
                    .create()

                closeButton?.setOnClickListener {
                    galleryDialog.dismiss()
                }

                val adapter = DeliveryPhotosAdapter(
                    photos = photos,
                    onImageClick = { imagePath -> openImageFullScreen(imagePath) },
                    onDeleteClick = { photo, position ->
                        ConfirmationDialog(activity, "Delete this delivery photo?") {
                            CoroutineScope(Dispatchers.IO).launch {
                                File(photo.imagePath).delete()
                                dao.deletePhoto(photo)

                                withContext(Dispatchers.Main) {
                                    activity.toast("Photo deleted")
                                    photos.removeAt(position)
                                    recyclerView.adapter?.notifyItemRemoved(position)
                                    photosRemaining--
                                    updateHeaderText()

                                    if (photosRemaining <= 0) {
                                        galleryDialog.dismiss()
                                        onGalleryEmpty?.invoke() // Tell the calling Activity the gallery is empty
                                    } else if (position == 0) {
                                        onNewestPhotoDeleted?.invoke(photos.first().imagePath) // Tell the calling Activity to load the next photo
                                    }
                                }
                            }
                        }
                    }
                )

                recyclerView.adapter = adapter
                galleryDialog.show()
                galleryDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            }
        }
    }

    private fun openImageFullScreen(imagePath: String) {
        try {
            val file = File(imagePath)
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            activity.toast("Could not open image.")
        }
    }
}

