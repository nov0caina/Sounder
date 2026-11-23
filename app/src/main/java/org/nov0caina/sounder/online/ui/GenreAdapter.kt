package org.nov0caina.sounder.online.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.ItemGenreCardBinding

data class GenreItem(
    val name: String,
    val query: String,
    val backgroundRes: Int,
    val iconRes: Int,
    @StringRes val nameRes: Int = 0
)

class GenreAdapter(
    private var genres: List<GenreItem> = DEFAULT_GENRES,
    private val onGenreClick: (GenreItem) -> Unit
) : RecyclerView.Adapter<GenreAdapter.ViewHolder>() {

    /**
     * Replaces the displayed genre items using DiffUtil so only toggled category cards update.
     */
    fun updateGenres(newGenres: List<GenreItem>) {
        if (genres == newGenres) return
        val oldGenres = genres
        val diff = DiffUtil.calculateDiff(
            object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldGenres.size
                override fun getNewListSize(): Int = newGenres.size
                override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                    oldGenres[oldPos].name == newGenres[newPos].name
                override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                    oldGenres[oldPos] == newGenres[newPos]
            }
        )
        genres = newGenres
        diff.dispatchUpdatesTo(this)
    }

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
            binding.genreTitle.text = if (genre.nameRes != 0) {
                binding.root.context.getString(genre.nameRes)
            } else {
                genre.name
            }
            binding.genreBg.setBackgroundResource(genre.backgroundRes)
            binding.genreIcon.setImageResource(genre.iconRes)
            binding.root.setOnClickListener {
                onGenreClick(genre)
            }
        }
    }

    companion object {
        const val GENRE_CHRISTIAN_WORSHIP = "Christian & Worship"
        const val GENRE_KPOP = "K-Pop"

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
            GenreItem("R&B & Soul", "R&B artists", R.drawable.bg_genre_rnb, R.drawable.ic_category_rnb_24),
            GenreItem(GENRE_CHRISTIAN_WORSHIP, "Christian worship artists", R.drawable.bg_genre_christian, R.drawable.ic_category_christian_24, R.string.genre_christian_worship),
            GenreItem(GENRE_KPOP, "K-Pop artists", R.drawable.bg_genre_kpop, R.drawable.ic_category_kpop_24)
        )

        /**
         * Returns the genre catalog filtered by optional category visibility settings.
         */
        fun filterGenres(showChristian: Boolean, showKpop: Boolean): List<GenreItem> =
            DEFAULT_GENRES.filter { genre ->
                when (genre.name) {
                    GENRE_CHRISTIAN_WORSHIP -> showChristian
                    GENRE_KPOP -> showKpop
                    else -> true
                }
            }
    }
}
