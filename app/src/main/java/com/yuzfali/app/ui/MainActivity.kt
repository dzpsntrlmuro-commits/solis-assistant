package com.yuzfali.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.yuzfali.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        playEntrance()

        binding.btnStart.setOnClickListener {
            startActivity(Intent(this, CaptureActivity::class.java))
        }
    }

    private fun playEntrance() {
        listOf(binding.brandTitle, binding.tagline, binding.btnStart).forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 28f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(180L * index)
                .setDuration(700L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
        binding.heroGlow.alpha = 0.2f
        binding.heroGlow.animate()
            .alpha(0.9f)
            .setDuration(1400L)
            .start()
    }
}
