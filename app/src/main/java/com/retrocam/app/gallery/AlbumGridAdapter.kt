package com.retrocam.app.gallery

import android.content.ContentResolver
import android.graphics.Bitmap
import android.util.LruCache
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.retrocam.app.R
import com.retrocam.app.library.model.AlbumItem
import java.util.concurrent.ExecutorService

class AlbumGridAdapter(
    private val resolver: ContentResolver,
    private val executor: ExecutorService,
    private val onAlbumClick: (AlbumItem) -> Unit
) : RecyclerView.Adapter<AlbumGridAdapter.Holder>() {

    private val albums = mutableListOf<AlbumItem>()
    private val cache = object : LruCache<String, Bitmap>(60) {}

    fun submitAlbums(newList: List<AlbumItem>) {
        albums.clear()
        albums.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_album, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = albums.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val album = albums[position]
        holder.title.text = album.name
        holder.mediaCount.text = "${album.mediaCount} items"

        holder.itemView.setOnClickListener { onAlbumClick(album) }

        val coverUri = album.coverUri
        if (coverUri != null) {
            holder.cover.tag = coverUri.toString()
            val cached = cache.get(coverUri.toString())
            if (cached != null) {
                holder.cover.setImageBitmap(cached)
                return
            }

            holder.cover.setImageBitmap(null)
            executor.execute {
                val thumbnail = try {
                    resolver.loadThumbnail(coverUri, Size(360, 360), null)
                } catch (e: Exception) {
                    null
                } ?: return@execute

                cache.put(coverUri.toString(), thumbnail)
                holder.cover.post {
                    if (holder.cover.tag == coverUri.toString()) {
                        holder.cover.setImageBitmap(thumbnail)
                    }
                }
            }
        } else {
            holder.cover.setImageResource(R.drawable.ic_gallery)
            holder.cover.imageTintList = android.content.res.ColorStateList.valueOf(0x44FFFFFF)
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: ImageView = view.findViewById(R.id.albumCover)
        val title: TextView = view.findViewById(R.id.albumTitle)
        val mediaCount: TextView = view.findViewById(R.id.albumMediaCount)
    }
}
