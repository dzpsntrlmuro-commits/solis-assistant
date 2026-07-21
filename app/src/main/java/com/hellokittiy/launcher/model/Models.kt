package com.hellokittiy.launcher.model

import android.graphics.drawable.Drawable

data class AppItem(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable
)

data class NotifItem(
    val key: String,
    val title: String,
    val text: String,
    val packageName: String
)
