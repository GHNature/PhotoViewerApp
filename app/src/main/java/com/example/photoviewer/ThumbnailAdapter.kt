package com.example.photoviewer

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.photoviewer.databinding.ItemThumbnailBinding
import java.io.File

class ThumbnailAdapter(
    private val photos: List<String>,
    private val selectedPaths: MutableSet<String>,
    private val exifToolManager: ExifToolManager,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<ThumbnailAdapter.VH>() {

    class VH(val binding: ItemThumbnailBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemThumbnailBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val path = photos[position]

        // 1. 读取当前排队的旋转角度，进行实时预览
        val currentPendingRotation = exifToolManager.rotationQueue[path] ?: 0
        holder.binding.ivThumbnail.rotation = currentPendingRotation.toFloat()

        // 2. 使用 Coil 加载缩略图
        holder.binding.ivThumbnail.load(File(path)) {
            crossfade(true)
        }

        // 3. 勾选框状态更新
        val isSelected = selectedPaths.contains(path)
        holder.binding.cbSelect.isChecked = isSelected

        // 4. 点击图片卡片切换选中状态
        holder.itemView.setOnClickListener {
            if (selectedPaths.contains(path)) {
                selectedPaths.remove(path)
            } else {
                selectedPaths.add(path)
            }
            notifyItemChanged(position)
            onSelectionChanged()
        }
    }

    override fun getItemCount(): Int = photos.size
}