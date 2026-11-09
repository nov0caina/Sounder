package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.nov0caina.sounder.databinding.ItemHomeMoodChipBinding
import org.nov0caina.sounder.online.model.HomeMoodItem

class HomeMoodAdapter(
    private val moods: List<HomeMoodItem>,
    private var selectedMoodId: String = "all",
    private val onMoodSelected: (HomeMoodItem) -> Unit
) : RecyclerView.Adapter<HomeMoodAdapter.ViewHolder>() {

    companion object {
        private const val PAYLOAD_CHECKED_STATE = "payload_checked_state"
    }

    fun setSelectedMood(moodId: String) {
        if (selectedMoodId != moodId) {
            val oldIdx = moods.indexOfFirst { it.id == selectedMoodId }
            val newIdx = moods.indexOfFirst { it.id == moodId }
            selectedMoodId = moodId
            if (oldIdx >= 0) notifyItemChanged(oldIdx, PAYLOAD_CHECKED_STATE)
            if (newIdx >= 0) notifyItemChanged(newIdx, PAYLOAD_CHECKED_STATE)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHomeMoodChipBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(moods[position])
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_CHECKED_STATE)) {
            holder.updateCheckedState(moods[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun getItemCount(): Int = moods.size

    inner class ViewHolder(
        private val binding: ItemHomeMoodChipBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(mood: HomeMoodItem) {
            binding.moodChip.isCheckedIconVisible = false
            binding.moodChip.setText(mood.nameRes)
            updateCheckedState(mood)
            binding.moodChip.setOnClickListener {
                if (mood.id != selectedMoodId) {
                    setSelectedMood(mood.id)
                    onMoodSelected(mood)
                } else {
                    binding.moodChip.isChecked = true
                }
            }
        }

        fun updateCheckedState(mood: HomeMoodItem) {
            val shouldBeChecked = (mood.id == selectedMoodId)
            if (binding.moodChip.isChecked != shouldBeChecked) {
                binding.moodChip.isChecked = shouldBeChecked
            }
        }
    }
}
