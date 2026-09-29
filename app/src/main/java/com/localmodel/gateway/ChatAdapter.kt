package com.localmodel.gateway

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class ChatMessage(
    val author: Author,
    val content: String,
    val thinking: String? = null,
    val thinkingExpanded: Boolean = false,

    val thinkingUserSet: Boolean = false,

    val tokensPerSecond: Double? = null,
    val images: List<String> = emptyList(),
) {
    enum class Author { USER, ASSISTANT }
}

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.Holder>() {

    private val items = ArrayList<ChatMessage>()

    var onToggleThinking: ((Int) -> Unit)? = null

    fun submit(list: List<ChatMessage>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun update(index: Int, message: ChatMessage) {
        if (index !in items.indices) return
        items[index] = message
        notifyItemChanged(index)
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = items[position].author.ordinal

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val layout = if (viewType == ChatMessage.Author.USER.ordinal) {
            R.layout.item_msg_user
        } else {
            R.layout.item_msg_ai
        }
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = items[position]
        holder.text.maxWidth = UiMetrics.bubbleMaxWidth(holder.text.context)

        val hasThinking = !message.thinking.isNullOrBlank()
        holder.thinkingContainer?.visibility = if (hasThinking) View.VISIBLE else View.GONE
        if (hasThinking) {
            holder.thinkingText?.text = message.thinking
            val expanded = message.thinkingExpanded
            holder.thinkingBody?.visibility = if (expanded) View.VISIBLE else View.GONE
            holder.thinkingArrow?.text = if (expanded) "▴" else "▾"
            holder.thinkingHeader?.setOnClickListener {
                val index = holder.bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onToggleThinking?.invoke(index)
            }
        }

        val blankAnswer = message.content.isEmpty()
        holder.text.text = when {
            !blankAnswer -> message.content
            hasThinking -> ""
            else -> "…"
        }
        holder.text.visibility = if (blankAnswer && hasThinking) View.GONE else View.VISIBLE

        holder.metaText?.apply {
            val tps = message.tokensPerSecond
            visibility = if (tps == null) View.GONE else View.VISIBLE
            if (tps != null) text = String.format(java.util.Locale.US, "%.1f tok/s", tps)
        }

        holder.imageContainer?.let { container ->
            container.removeAllViews()
            container.visibility = if (message.images.isEmpty()) View.GONE else View.VISIBLE
            val size = UiMetrics.thumbnailSize(container.context)
            message.images.forEach { uri ->
                val image = ImageView(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(size, size).apply { bottomMargin = 6 }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageURI(Uri.parse(uri))
                }
                container.addView(image)
            }
        }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.messageText)
        val thinkingContainer: LinearLayout? = view.findViewById(R.id.thinkingContainer)
        val thinkingHeader: LinearLayout? = view.findViewById(R.id.thinkingHeader)
        val thinkingArrow: TextView? = view.findViewById(R.id.thinkingArrow)
        val thinkingBody: LinearLayout? = view.findViewById(R.id.thinkingBody)
        val thinkingText: TextView? = view.findViewById(R.id.thinkingText)
        val metaText: TextView? = view.findViewById(R.id.metaText)
        val imageContainer: LinearLayout? = view.findViewById(R.id.imageContainer)
    }
}
