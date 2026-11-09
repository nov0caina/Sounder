package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemGenreCardBinding

data class GenreItem(
    val name: String,
    val query: String,
    val backgroundRes: Int,
    val iconRes: Int
)

class GenreAdapter(
    private val genres: List<GenreItem> = DEFAULT_GENRES,
    private val onGenreClick: (GenreItem) -> Unit
) : RecyclerView.Adapter<GenreAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemGenreCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(genres[position])
    }

    override fun getItemCount(): Int = genres.size

    inner class ViewHolder(
        private val binding: ItemGenreCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(genre: GenreItem) {
            binding.genreTitle.text = genre.name
            binding.genreBg.setBackgroundResource(genre.backgroundRes)
            binding.genreIcon.setImageResource(genre.iconRes)
            binding.root.setOnClickListener {
                onGenreClick(genre)
            }
        }
    }

    companion object {
        val DEFAULT_GENRES = listOf(
            GenreItem("Rock", "Rock artists", R.drawable.bg_genre_rock, R.drawable.ic_category_rock_24),
            GenreItem("Metal", "Metal artists", R.drawable.bg_genre_metal, R.drawable.ic_category_metal_24),
            GenreItem("Pop", "Pop artists", R.drawable.bg_genre_pop, R.drawable.ic_category_pop_24),
            GenreItem("Hip-Hop & Rap", "Hip Hop artists", R.drawable.bg_genre_hiphop, R.drawable.ic_category_hiphop_24),
            GenreItem("Electronic / Dance", "Electronic music artists", R.drawable.bg_genre_electronic, R.drawable.ic_category_electronic_24),
            GenreItem("Jazz & Blues", "Jazz artists", R.drawable.bg_genre_jazz, R.drawable.ic_category_jazz_24),
            GenreItem("Latin & Reggaeton", "Latin artists", R.drawable.bg_genre_latin, R.drawable.ic_category_latin_24),
            GenreItem("Classical", "Classical music composers", R.drawable.bg_genre_classical, R.drawable.ic_category_classical_24),
            GenreItem("Indie & Alternative", "Indie rock artists", R.drawable.bg_genre_indie, R.drawable.ic_category_indie_24),
            GenreItem("R&B & Soul", "R&B artists", R.drawable.bg_genre_rnb, R.drawable.ic_category_rnb_24)
        )
    }
}
