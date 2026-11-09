package org.nov0caina.sounder.online.ui

import android.app.Activity
import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.snackbar.Snackbar
import org.nov0caina.sounder.R
import org.nov0caina.sounder.databinding.LayoutOfflineStateBinding
import org.nov0caina.sounder.util.NetworkUtil

data class OfflineProposal(
    @DrawableRes val iconRes: Int,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    @StringRes val retryRes: Int,
    @StringRes val libraryRes: Int
)

object OfflineStateHelper {

    val proposals = listOf(
        OfflineProposal(
            R.drawable.ic_offline_fun_music,
            R.string.home_offline_title_1,
            R.string.home_offline_sub_1,
            R.string.home_offline_retry_1,
            R.string.home_offline_lib_1
        ),
        OfflineProposal(
            R.drawable.ic_offline_space,
            R.string.home_offline_title_2,
            R.string.home_offline_sub_2,
            R.string.home_offline_retry_2,
            R.string.home_offline_lib_2
        ),
        OfflineProposal(
            R.drawable.ic_offline_unplugged,
            R.string.home_offline_title_3,
            R.string.home_offline_sub_3,
            R.string.home_offline_retry_3,
            R.string.home_offline_lib_3
        ),
        OfflineProposal(
            R.drawable.ic_offline_coffee_vinyl,
            R.string.home_offline_title_4,
            R.string.home_offline_sub_4,
            R.string.home_offline_retry_4,
            R.string.home_offline_lib_4
        )
    )

    fun getNextProposal(context: Context): OfflineProposal {
        val prefs = context.getSharedPreferences("sounder_offline_prefs", Context.MODE_PRIVATE)
        val currentIndex = prefs.getInt("proposal_index", 0)
        val nextIndex = (currentIndex + 1) % proposals.size
        prefs.edit().putInt("proposal_index", nextIndex).apply()
        return proposals[currentIndex % proposals.size]
    }

    fun applyProposal(
        binding: LayoutOfflineStateBinding,
        proposal: OfflineProposal,
        customError: String? = null
    ) {
        binding.offlineStateIcon.setImageResource(proposal.iconRes)
        if (customError != null) {
            binding.offlineStateTitle.setText(R.string.home_feed_error)
            binding.offlineStateSubtitle.text = customError
        } else {
            binding.offlineStateTitle.setText(proposal.titleRes)
            binding.offlineStateSubtitle.setText(proposal.subtitleRes)
        }
        binding.offlineStateRetryBtn.setText(proposal.retryRes)
        binding.offlineStateSecondaryBtn.setText(proposal.libraryRes)
    }

    fun setup(
        binding: LayoutOfflineStateBinding,
        onRetry: () -> Unit,
        onSecondaryClick: (() -> Unit)? = null,
        customError: String? = null
    ): OfflineProposal {
        val context = binding.root.context
        val initialProposal = getNextProposal(context)
        applyProposal(binding, initialProposal, customError)

        var currentProposal = initialProposal

        binding.offlineStateRetryBtn.setOnClickListener {
            if (!NetworkUtil.isConnected(binding.root.context)) {
                Snackbar.make(
                    binding.root,
                    R.string.home_offline_still_no_internet,
                    Snackbar.LENGTH_SHORT
                ).show()
                // Cycle to the next humorous proposal on failed retry
                currentProposal = getNextProposal(binding.root.context)
                applyProposal(binding, currentProposal, customError)
            } else {
                onRetry()
            }
        }

        binding.offlineStateSecondaryBtn.setOnClickListener {
            if (onSecondaryClick != null) {
                onSecondaryClick()
            } else {
                // Default fallback: Switch bottom navigation to the Library tab
                val activity = findActivity(binding.root.context)
                activity?.findViewById<NavigationBarView>(R.id.main_bottom_nav)?.selectedItemId = R.id.nav_library
            }
        }

        return initialProposal
    }

    private fun findActivity(context: Context): Activity? {
        var ctx = context
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }
}
