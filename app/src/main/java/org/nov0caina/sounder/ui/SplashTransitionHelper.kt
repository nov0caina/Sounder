package org.nov0caina.sounder.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.animation.doOnEnd
import org.nov0caina.sounder.R

/**
 * Coordinates the X/Twitter-style scale-up and fade-out launch transition from the Sounder splash
 * screen into foreground app content.
 */
object SplashTransitionHelper {
    const val EXIT_DURATION_MS = 380L
    const val INITIAL_SCALE = 1.0f
    const val TARGET_SCALE = 12.0f
    const val SPLASH_BACKGROUND_HEX = "#12131C"

    data class SplashFrameState(
        val scale: Float,
        val iconAlpha: Float,
        val containerAlpha: Float,
    )

    /**
     * Computes scale and alpha values for a given normalized animation fraction in [0f, 1f].
     */
    fun calculateScaleAndAlpha(fraction: Float): SplashFrameState {
        val t = fraction.coerceIn(0f, 1f)
        val accelerated = t * t
        val scale = INITIAL_SCALE + (TARGET_SCALE - INITIAL_SCALE) * accelerated
        val iconAlpha = (1f - accelerated).coerceIn(0f, 1f)
        val containerAlpha = (1f - (t * 1.15f).coerceIn(0f, 1f))
        return SplashFrameState(
            scale = scale,
            iconAlpha = iconAlpha,
            containerAlpha = containerAlpha,
        )
    }

    /**
     * Installs the splash exit animation for cold starts. Uses native Android 12+ SplashScreen API
     * when available, falling back to an identical overlay transition on older Android versions.
     */
    fun attach(activity: Activity, isColdStart: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.splashScreen.setOnExitAnimationListener { splashScreenView ->
                runExitAnimator(
                    container = splashScreenView,
                    iconView = splashScreenView.iconView,
                    onComplete = { splashScreenView.remove() },
                )
            }
        } else if (isColdStart) {
            val decorView = activity.window.decorView as? ViewGroup ?: return
            val iconSizePx = (288 * activity.resources.displayMetrics.density).toInt()
            val overlay =
                FrameLayout(activity).apply {
                    setBackgroundColor(Color.parseColor(SPLASH_BACKGROUND_HEX))
                    isClickable = true
                    isFocusable = true
                }
            val iconView =
                ImageView(activity).apply {
                    setImageResource(R.drawable.ic_sounder_splash)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
            overlay.addView(
                iconView,
                FrameLayout.LayoutParams(iconSizePx, iconSizePx, Gravity.CENTER),
            )
            decorView.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            overlay.post {
                runExitAnimator(
                    container = overlay,
                    iconView = iconView,
                    onComplete = { decorView.removeView(overlay) },
                )
            }
        }
    }

    private fun runExitAnimator(container: View, iconView: View?, onComplete: () -> Unit) {
        val containerFade =
            ObjectAnimator.ofPropertyValuesHolder(
                    container,
                    PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
                )
                .apply {
                    interpolator = AccelerateInterpolator(1.2f)
                    duration = EXIT_DURATION_MS
                    doOnEnd { onComplete() }
                }

        val set = AnimatorSet()
        if (iconView != null) {
            val iconAnim =
                ObjectAnimator.ofPropertyValuesHolder(
                        iconView,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, INITIAL_SCALE, TARGET_SCALE),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, INITIAL_SCALE, TARGET_SCALE),
                        PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0f),
                    )
                    .apply {
                        interpolator = AccelerateInterpolator(1.6f)
                        duration = EXIT_DURATION_MS
                    }
            set.playTogether(iconAnim, containerFade)
        } else {
            set.play(containerFade)
        }
        set.start()
    }
}
