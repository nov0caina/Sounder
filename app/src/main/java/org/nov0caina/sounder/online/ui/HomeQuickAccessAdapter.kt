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
import org.nov0caina.sounder.databinding.ItemHomeQuickAccessBinding
import org.nov0caina.sounder.online.model.QuickAccessItem
import org.nov0caina.sounder.util.getAttrColorCompat

class HomeQuickAccessAdapter(
    private val onItemClick: (QuickAccessItem) -> Unit,
    private val onPlayClick: (QuickAccessItem) -> Unit,
    private val isItemPlaying: (QuickAccessItem) -> Boolean,
    private val onItemLongClick: ((QuickAccessItem) -> Unit)? = null
) : ListAdapter<QuickAccessItem, HomeQuickAccessAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHomeQuickAccessBinding.inflate(
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
        private val binding: ItemHomeQuickAccessBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: QuickAccessItem) {
            val context = binding.root.context
            val isPlaying = isItemPlaying(item)

            binding.quickAccessTitle.text = item.title
            binding.quickAccessSubtitle.isVisible = !item.subtitle.isNullOrBlank()
            binding.quickAccessSubtitle.text = item.subtitle

            if (item.isFavorites) {
                binding.quickAccessImage.isVisible = false
                binding.quickAccessIcon.isVisible = true
                binding.quickAccessIcon.setImageResource(item.iconRes ?: R.drawable.ic_favorite_24)
            } else if (!item.imageUrl.isNullOrBlank()) {
                binding.quickAccessIcon.isVisible = false
                binding.quickAccessImage.isVisible = true
                binding.quickAccessImage.load(item.imageUrl) {
                    crossfade(true)
                }
            } else {
                binding.quickAccessImage.isVisible = false
                binding.quickAccessIcon.isVisible = true
                binding.quickAccessIcon.setImageResource(item.iconRes ?: R.drawable.ic_song_24)
            }

            // Playback indicator & button state
            if (isPlaying) {
                binding.quickAccessPlayBtn.setImageResource(R.drawable.ic_pause_24)
                binding.quickAccessPlayBtn.contentDescription = context.getString(R.string.btn_pause)
                binding.quickAccessCard.strokeColor = context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary).defaultColor
                binding.quickAccessCard.strokeWidth = (2 * context.resources.displayMetrics.density).toInt().coerceAtLeast(2)
                binding.quickAccessTitle.setTextColor(context.getAttrColorCompat(androidx.appcompat.R.attr.colorPrimary))
            } else {
                binding.quickAccessPlayBtn.setImageResource(R.drawable.ic_play_24)
                binding.quickAccessPlayBtn.contentDescription = context.getString(R.string.btn_play)
                binding.quickAccessCard.strokeColor = context.getAttrColorCompat(com.google.android.material.R.attr.colorOutlineVariant).defaultColor
                binding.quickAccessCard.strokeWidth = (1 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                binding.quickAccessTitle.setTextColor(context.getAttrColorCompat(com.google.android.material.R.attr.colorOnSurface))
            }

            binding.root.setOnClickListener {
                onItemClick(item)
            }

            binding.root.setOnLongClickListener {
                if (onItemLongClick != null) {
                    onItemLongClick.invoke(item)
                    true
                } else {
                    false
                }
            }

            binding.quickAccessPlayBtn.setOnClickListener {
                onPlayClick(item)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<QuickAccessItem>() {
        override fun areItemsTheSame(oldItem: QuickAccessItem, newItem: QuickAccessItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: QuickAccessItem, newItem: QuickAccessItem): Boolean =
            oldItem == newItem
    }
}
