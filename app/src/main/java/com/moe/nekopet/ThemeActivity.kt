package com.moe.nekopet

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.DynamicColors
import com.moe.nekopet.databinding.ActivityThemeBinding

class ThemeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityThemeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        val cfg = ThemeStore.load(this)
        setTheme(ThemeStore.themeStyleRes(cfg.palette))
        super.onCreate(savedInstanceState)
        if (cfg.palette == Palette.SYSTEM) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        binding = ActivityThemeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge()

        when (cfg.themeMode) {
            ThemeMode.LIGHT -> binding.modeLight.isChecked = true
            ThemeMode.DARK -> binding.modeDark.isChecked = true
            ThemeMode.FOLLOW -> binding.modeFollow.isChecked = true
        }
        binding.themeModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val m = when (checkedId) {
                binding.modeLight.id -> ThemeMode.LIGHT
                binding.modeDark.id -> ThemeMode.DARK
                else -> ThemeMode.FOLLOW
            }
            apply(m, ThemeStore.load(this).palette)
        }

        renderPalettes()
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.setPadding(0, top, 0, 0)
            insets
        }
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !dark
    }

    /** 渲染配色选择：跟随系统 + 5 个预设圆形色块 */
    private fun renderPalettes() {
        binding.paletteRow.removeAllViews()
        val current = ThemeStore.load(this).palette
        val size = dp(56)
        for (pal in Palette.entries) {
            val view = View(this)
            view.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            }

            val shape = GradientDrawable()
            shape.shape = GradientDrawable.OVAL
            if (pal == Palette.SYSTEM) {
                shape.colors = intArrayOf(
                    Color.parseColor("#4285F4"),
                    Color.parseColor("#EA4335"),
                    Color.parseColor("#FBBC05"),
                    Color.parseColor("#34A853")
                )
                shape.gradientType = GradientDrawable.SWEEP_GRADIENT
                shape.setGradientCenter(0.5f, 0.5f)
            } else {
                shape.setColor(getPaletteColor(pal))
            }
            val border = ContextCompat.getColor(this, android.R.color.white)
            if (pal == current) {
                shape.setStroke(dp(4), border)
            }
            view.background = shape
            view.setOnClickListener {
                apply(ThemeStore.load(this).themeMode, pal)
                renderPalettes()
            }
            binding.paletteRow.addView(view)
        }
    }

    private fun getPaletteColor(pal: Palette): Int = when (pal) {
        Palette.SYSTEM -> Color.TRANSPARENT
        Palette.PURPLE -> ContextCompat.getColor(this, R.color.pal_purple_primary)
        Palette.BLUE -> ContextCompat.getColor(this, R.color.pal_blue_primary)
        Palette.GREEN -> ContextCompat.getColor(this, R.color.pal_green_primary)
        Palette.PINK -> ContextCompat.getColor(this, R.color.pal_pink_primary)
        Palette.ORANGE -> ContextCompat.getColor(this, R.color.pal_orange_primary)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun apply(mode: ThemeMode, palette: Palette) {
        ThemeStore.save(this, mode, palette)
        AppCompatDelegate.setDefaultNightMode(ThemeStore.appcompatNightMode(mode))
        recreate()
    }
}