package com.localmodel.gateway

import android.content.Context

object UiMetrics {

    fun bubbleMaxWidth(context: Context): Int =
        (context.resources.displayMetrics.widthPixels * 0.86f).toInt()

    fun thumbnailSize(context: Context): Int =
        (context.resources.displayMetrics.widthPixels * 0.44f).toInt()
}
