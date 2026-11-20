/*
 * Copyright (c) 2024 Auxio Project
 * LocationAdapter.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.nov0caina.sounder.music.locations

import android.annotation.SuppressLint
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemMusicLocationBinding
import org.nov0caina.sounder.databinding.ItemNoLocationsBinding
import org.nov0caina.sounder.util.context
import org.nov0caina.sounder.util.getPlural
import org.nov0caina.sounder.util.inflater
import org.oxycblt.musikr.fs.Location
import org.oxycblt.musikr.fs.mediastore.DiscoveredMusicFolder

sealed interface FolderItem {
    data class Discovered(
        val folder: DiscoveredMusicFolder,
        val isSelected: Boolean,
    ) : FolderItem

    data class Custom(val location: Location.Opened) : FolderItem
}

class LocationAdapter(private val listener: Listener) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val _items = mutableListOf<FolderItem>()

    override fun getItemCount() = if (_items.isEmpty()) 1 else _items.size

    override fun getItemViewType(position: Int): Int =
        if (_items.isEmpty()) VIEW_TYPE_EMPTY else VIEW_TYPE_LOCATION

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_EMPTY -> EmptyLocationViewHolder.from(parent)
            VIEW_TYPE_LOCATION -> LocationViewHolder.from(parent)
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is LocationViewHolder -> holder.bind(_items[position], listener)
            is EmptyLocationViewHolder -> holder.bind()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitItems(items: List<FolderItem>) {
        _items.clear()
        _items.addAll(items)
        notifyDataSetChanged()
    }

    interface Listener {
        fun onToggleDiscovered(folder: DiscoveredMusicFolder, isChecked: Boolean)

        fun onRemoveCustom(location: Location.Opened)
    }

    companion object {
        private const val VIEW_TYPE_EMPTY = 0
        private const val VIEW_TYPE_LOCATION = 1
    }
}

class LocationViewHolder(private val binding: ItemMusicLocationBinding) :
    RecyclerView.ViewHolder(binding.root) {

    fun bind(item: FolderItem, listener: LocationAdapter.Listener) {
        val context = binding.context
        when (item) {
            is FolderItem.Discovered -> {
                val folder = item.folder
                binding.locationName.text = folder.displayName
                binding.locationPath.text = folder.path.resolve(context)
                val songCountText = context.getPlural(R.plurals.fmt_song_count, folder.songCount)
                binding.locationSubtitle.text =
                    if (folder.isSounderManaged) {
                        "$songCountText · ${context.getString(R.string.lbl_folder_always_included)}"
                    } else {
                        songCountText
                    }

                binding.locationDelete.isVisible = false
                binding.locationDelete.setOnClickListener(null)

                binding.locationCheckbox.isVisible = true
                binding.locationCheckbox.setOnCheckedChangeListener(null)
                if (folder.isSounderManaged) {
                    binding.locationCheckbox.isChecked = true
                    binding.locationCheckbox.isEnabled = false
                    binding.root.isClickable = false
                    binding.root.setOnClickListener(null)
                } else {
                    binding.locationCheckbox.isEnabled = true
                    binding.locationCheckbox.isChecked = item.isSelected
                    binding.locationCheckbox.setOnCheckedChangeListener { _, isChecked ->
                        listener.onToggleDiscovered(folder, isChecked)
                    }
                    binding.root.isClickable = true
                    binding.root.setOnClickListener { binding.locationCheckbox.toggle() }
                }
            }
            is FolderItem.Custom -> {
                val location = item.location
                binding.locationName.text = location.path.name ?: location.path.resolve(context)
                binding.locationPath.text = location.path.resolve(context)
                binding.locationSubtitle.setText(R.string.lbl_custom_folder)

                binding.locationCheckbox.setOnCheckedChangeListener(null)
                binding.locationCheckbox.isVisible = false

                binding.locationDelete.isVisible = true
                binding.locationDelete.setOnClickListener { listener.onRemoveCustom(location) }

                binding.root.isClickable = false
                binding.root.setOnClickListener(null)
            }
        }
    }

    companion object {
        fun from(parent: ViewGroup) =
            LocationViewHolder(
                ItemMusicLocationBinding.inflate(parent.context.inflater, parent, false)
            )
    }
}

class EmptyLocationViewHolder(private val binding: ItemNoLocationsBinding) :
    RecyclerView.ViewHolder(binding.root) {

    fun bind() {
        binding.locationPath.setText(R.string.lbl_no_music_folders_found)
    }

    companion object {
        fun from(parent: ViewGroup) =
            EmptyLocationViewHolder(
                ItemNoLocationsBinding.inflate(parent.context.inflater, parent, false)
            )
    }
}
