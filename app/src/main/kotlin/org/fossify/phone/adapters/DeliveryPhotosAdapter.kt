package org.fossify.phone.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.phone.R
import org.fossify.phone.data.CallerPhotoEntity
import org.fossify.phone.helpers.DeliveryPhotoHelper
import org.fossify.commons.extensions.toast
import androidx.lifecycle.lifecycleScope

class DeliveryPhotosAdapter(
    var photos: MutableList<CallerPhotoEntity>,
    private val onImageClick: (String) -> Unit,
    private val onDeleteClick: (CallerPhotoEntity, Int) -> Unit
) : RecyclerView.Adapter<DeliveryPhotosAdapter.PhotoViewHolder>() {

    inner class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.delivery_photo_image)
        val dateText: TextView = view.findViewById(R.id.delivery_photo_date)
        val deleteText: TextView = view.findViewById(R.id.delivery_photo_delete)
        val favoriteIcon: ImageView = view.findViewById(R.id.delivery_photo_favorite)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_delivery_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun getItemCount() = photos.size

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photo = photos[position]

        Glide.with(holder.itemView.context)
            .load(photo.imagePath)
            .into(holder.imageView)

        // Set Date
        holder.dateText.text = android.text.format.DateFormat.format("MMM dd, yyyy - hh:mm a", photo.createdAt)

        // Favorite State
        val favoriteAlpha = if (photo.isFavorite) 1.0f else 0.3f
        holder.favoriteIcon.alpha = favoriteAlpha

        // Click Listeners
        holder.imageView.setOnClickListener { onImageClick(photo.imagePath) }
        holder.deleteText.setOnClickListener { onDeleteClick(photo, holder.adapterPosition) }

        holder.favoriteIcon.setOnClickListener {
            val context = holder.itemView.context
            val scope = (context as? androidx.fragment.app.FragmentActivity)?.lifecycleScope ?: return@setOnClickListener
            
            scope.launch {
                val success = DeliveryPhotoHelper.toggleFavorite(context, photo)
                if (success) {
                    val updatedPhoto = photo.copy(isFavorite = !photo.isFavorite)
                    photos[holder.adapterPosition] = updatedPhoto

                    // Re-sort the list so favorites stay at the top
                    photos.sortWith(compareByDescending<CallerPhotoEntity> { it.isFavorite }.thenByDescending { it.createdAt })
                    notifyDataSetChanged()
                } else {
                    context.toast("You can only have up to 3 favorite photos.")
                }
            }
        }
    }
}
