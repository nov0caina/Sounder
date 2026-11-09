package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemDownloadBinding
import org.nov0caina.sounder.online.download.DownloadItem
import org.nov0caina.sounder.online.download.DownloadStatus

class DownloadsAdapter(
    private val onCancelClick: (DownloadItem) -> Unit,
    private val onItemClick: (DownloadItem) -> Unit = {}
) : ListAdapter<DownloadItem, DownloadsAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(
        private val binding: ItemDownloadBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: DownloadItem) {
            binding.downloadItemTitle.text = item.title

            if (!item.artworkUrl.isNullOrBlank()) {
                binding.downloadItemImage.load(item.artworkUrl) {
                    crossfade(true)
                }
            } else {
                binding.downloadItemImage.setImageResource(R.drawable.ic_download_24)
            }

            when (item.status) {
                DownloadStatus.QUEUED -> {
                    binding.downloadItemSubtitle.text = "${item.artist} • En cola"
                    binding.downloadItemProgress.isVisible = true
                    binding.downloadItemProgress.isIndeterminate = true
                    binding.downloadItemCancel.isVisible = true
                }
                DownloadStatus.DOWNLOADING -> {
                    binding.downloadItemSubtitle.text = "${item.artist} • ${item.progress}%"
                    binding.downloadItemProgress.isVisible = true
                    binding.downloadItemProgress.isIndeterminate = false
                    binding.downloadItemProgress.progress = item.progress
                    binding.downloadItemCancel.isVisible = true
                }
                DownloadStatus.TAGGING -> {
                    binding.downloadItemSubtitle.text = "${item.artist} • Aplicando etiquetas..."
                    binding.downloadItemProgress.isVisible = true
                    binding.downloadItemProgress.isIndeterminate = true
                    binding.downloadItemCancel.isVisible = false
                }
                DownloadStatus.COMPLETED -> {
                    binding.downloadItemSubtitle.text = "${item.artist} • Completado"
                    binding.downloadItemProgress.isVisible = false
                    binding.downloadItemCancel.isVisible = false
                }
                DownloadStatus.FAILED -> {
                    binding.downloadItemSubtitle.text = "Error: ${item.error ?: "Descarga fallida"}"
                    binding.downloadItemProgress.isVisible = false
                    binding.downloadItemCancel.isVisible = true
                }
                DownloadStatus.CANCELLED -> {
                    binding.downloadItemSubtitle.text = "${item.artist} • Cancelado"
                    binding.downloadItemProgress.isVisible = false
                    binding.downloadItemCancel.isVisible = false
                }
            }

            binding.downloadItemCancel.setOnClickListener {
                onCancelClick(item)
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<DownloadItem>() {
        override fun areItemsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean =
            oldItem.trackId == newItem.trackId

        override fun areContentsTheSame(oldItem: DownloadItem, newItem: DownloadItem): Boolean =
            oldItem == newItem
    }
}

