package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import coil3.util.CoilUtils
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemOnlineArtistBinding
import org.nov0caina.sounder.databinding.ItemOnlineArtistGridBinding
import org.nov0caina.sounder.online.model.OnlineArtistSummary

class OnlineArtistAdapter(
    private val onArtistClick: (OnlineArtistSummary) -> Unit
) : RecyclerView.Adapter<OnlineArtistAdapter.ViewHolder>() {

    private var items: List<OnlineArtistSummary> = emptyList()

    val currentList: List<OnlineArtistSummary>
        get() = items

    var useGridLayout: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                notifyDataSetChanged()
            }
        }

    fun submitList(newList: List<OnlineArtistSummary>?) {
        val target = newList ?: emptyList()
        val old = items
        if (old === target) return

        when {
            old.isEmpty() && target.isEmpty() -> return
            old.isEmpty() -> {
                items = target
                notifyItemRangeInserted(0, target.size)
            }
            target.isEmpty() -> {
                val count = old.size
                items = emptyList()
                notifyItemRangeRemoved(0, count)
            }
            isIdPrefix(old, target) -> {
                val start = old.size
                items = target
                notifyItemRangeInserted(start, target.size - start)
            }
            isIdPrefix(target, old) -> {
                val removeStart = target.size
                val removeCount = old.size - target.size
                items = target
                notifyItemRangeRemoved(removeStart, removeCount)
            }
            else -> {
                items = target
                notifyDataSetChanged()
            }
        }
    }

    private fun isIdPrefix(
        shorter: List<OnlineArtistSummary>,
        longer: List<OnlineArtistSummary>
    ): Boolean {
        if (shorter.size >= longer.size) return false
        for (i in shorter.indices) {
            if (shorter[i].id != longer[i].id) return false
        }
        return true
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (useGridLayout) VIEW_TYPE_GRID else VIEW_TYPE_LIST

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            val binding = ItemOnlineArtistGridBinding.inflate(inflater, parent, false)
            ViewHolder(binding.root, binding.artistAvatar, binding.artistName, binding.artistSubtitle)
        } else {
            val binding = ItemOnlineArtistBinding.inflate(inflater, parent, false)
            ViewHolder(binding.root, binding.artistAvatar, binding.artistName, binding.artistSubtitle)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(
        rootView: View,
        private val avatarView: ImageView,
        private val nameView: TextView,
        private val subtitleView: TextView,
    ) : RecyclerView.ViewHolder(rootView) {

        fun bind(artist: OnlineArtistSummary) {
            nameView.text = artist.name
            subtitleView.isVisible = !artist.subtitle.isNullOrBlank()
            subtitleView.text = artist.subtitle

            CoilUtils.dispose(avatarView)
            avatarView.setImageDrawable(null)
            if (!artist.avatarUrl.isNullOrBlank()) {
                avatarView.load(artist.avatarUrl) {
                    crossfade(true)
                }
            } else {
                avatarView.setImageResource(R.drawable.ic_artist_24)
            }

            itemView.setOnClickListener {
                onArtistClick(artist)
            }
        }
    }

    private companion object {
        const val VIEW_TYPE_LIST = 0
        const val VIEW_TYPE_GRID = 1
    }
}
