package org.nov0caina.sounder.online.lyrics

import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.appcompat.R as AR
import com.google.android.material.R as MR
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemLyricLineBinding
import org.nov0caina.sounder.online.model.LrcLine
import org.nov0caina.sounder.util.getAttrColorCompat

class LyricLineAdapter(
    private val onLineClick: (Int, LrcLine) -> Unit
) : RecyclerView.Adapter<LyricLineAdapter.ViewHolder>() {

    private var lines: List<LrcLine> = emptyList()
    var activeIndex: Int = -1
        private set

    fun submitList(newLines: List<LrcLine>) {
        lines = newLines
        activeIndex = -1
        notifyDataSetChanged()
    }

    fun setActiveLine(index: Int): Boolean {
        if (index == activeIndex || index !in lines.indices) return false
        val oldIndex = activeIndex
        activeIndex = index
        if (oldIndex in lines.indices) {
            notifyItemChanged(oldIndex)
        }
        notifyItemChanged(activeIndex)
        return true
    }

    fun getLine(position: Int): LrcLine? = lines.getOrNull(position)

    override fun getItemCount(): Int = lines.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLyricLineBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(lines[position], position == activeIndex)
    }

    inner class ViewHolder(
        private val binding: ItemLyricLineBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(line: LrcLine, isActive: Boolean) {
            val context = binding.root.context
            binding.lyricLineText.text = line.text

            if (isActive) {
                binding.lyricLineText.alpha = 1.0f
                binding.lyricLineText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                binding.lyricLineText.setTypeface(null, Typeface.BOLD)
                binding.lyricLineText.setTextColor(context.getAttrColorCompat(AR.attr.colorPrimary))
            } else {
                binding.lyricLineText.alpha = 0.45f
                binding.lyricLineText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                binding.lyricLineText.setTypeface(null, Typeface.NORMAL)
                binding.lyricLineText.setTextColor(context.getAttrColorCompat(MR.attr.colorOnSurface))
            }

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos in lines.indices) {
                    onLineClick(pos, lines[pos])
                }
            }
        }
    }
}

