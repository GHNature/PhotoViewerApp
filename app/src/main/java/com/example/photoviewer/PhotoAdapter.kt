package com.example.photoviewer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.photoviewer.databinding.ItemPhotoBinding
import coil.load
import java.io.File

class PhotoAdapter(
    private val photos: List<String>,
    private val exifToolManager: ExifToolManager
) : RecyclerView.Adapter<PhotoAdapter.PhotoViewHolder>() {

    class PhotoViewHolder(val binding: ItemPhotoBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val binding = ItemPhotoBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PhotoViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val path = photos[position]
        val pendingAngle = exifToolManager.getPendingRotation(path)

        holder.binding.imageView.rotation = pendingAngle.toFloat()
        holder.binding.imageView.load(File(path)) {
            crossfade(true)
        }
    }

    override fun getItemCount(): Int = photos.size
}
