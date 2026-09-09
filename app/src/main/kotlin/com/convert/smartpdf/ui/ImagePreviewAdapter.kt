package com.convert.smartpdf.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.convert.smartpdf.R
import com.google.android.material.button.MaterialButton
import java.util.Collections

class ImagePreviewAdapter(
    val imageList: MutableList<Uri>,
    private val onUpdate: () -> Unit
) : RecyclerView.Adapter<ImagePreviewAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivSelectedImage: ImageView = view.findViewById(R.id.ivSelectedImage)
        val btnRemoveImage: MaterialButton = view.findViewById(R.id.btnRemoveImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_image_preview, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.ivSelectedImage.setImageURI(imageList[position])
        holder.btnRemoveImage.setOnClickListener {
            imageList.removeAt(holder.adapterPosition)
            notifyDataSetChanged()
            onUpdate()
        }
    }

    // ড্র্যাগ করে পজিশন পরিবর্তন করার লজিক
    fun onItemMove(fromPosition: Int, toPosition: Int) {
        Collections.swap(imageList, fromPosition, toPosition)
        notifyItemMoved(fromPosition, toPosition)
    }

    override fun getItemCount(): Int = imageList.size
}
