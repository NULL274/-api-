package com.localmodel.gateway

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ProgressDialogHandle internal constructor(
    private val dialog: AlertDialog,
    private val messageView: TextView,
    private val bar: ProgressBar?,
) {
    val isShowing: Boolean
        get() = dialog.isShowing

    fun setMessage(message: String) {
        messageView.text = message
    }

    fun setProgress(percent: Int) {
        bar?.progress = percent
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }
}

fun showProgressDialog(
    context: Context,
    message: String,
    horizontal: Boolean = false,
): ProgressDialogHandle {
    val padding = (20 * context.resources.displayMetrics.density).toInt()
    val messageView = TextView(context).apply {
        text = message
        textSize = 15f
    }
    val bar = if (horizontal) {
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
    } else {
        ProgressBar(context).apply { isIndeterminate = true }
    }
    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(padding, padding, padding, padding / 2)
        addView(messageView)
        addView(
            bar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = padding / 2 },
        )
    }
    val dialog = MaterialAlertDialogBuilder(context)
        .setView(container)
        .setCancelable(false)
        .create()
    dialog.setCanceledOnTouchOutside(false)
    dialog.show()
    return ProgressDialogHandle(dialog, messageView, bar)
}
