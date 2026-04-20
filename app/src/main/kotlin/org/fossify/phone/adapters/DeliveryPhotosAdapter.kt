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
import org.fossify.phone.R
import org.fossify.phone.data.CallerPhotoEntity

class DeliveryPhotosAdapter(
    var photos: MutableList<CallerPhotoEntity>,
    private val onImageClick: (String) -> Unit,
    private val onDeleteClick: (CallerPhotoEntity, Int) -> Unit
) : RecyclerView.Adapter<DeliveryPhotosAdapter.PhotoViewHolder>() {

    inner class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.delivery_photo_image)
        val dateText: TextView = view.findViewById(R.id.delivery_photo_date)
        val deleteText: TextView = view.findViewById(R.id.delivery_photo_delete)
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

        // Click Listeners
        holder.imageView.setOnClickListener { onImageClick(photo.imagePath) }
        holder.deleteText.setOnClickListener { onDeleteClick(photo, holder.adapterPosition) }
    }
}
