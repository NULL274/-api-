package com.localmodel.gateway

object PromptFormat {

    enum class Family { GEMMA, LLAMA3, MISTRAL, PHI3, QWEN, CHATML }

    fun detect(hint: String?): Family {
        val t = hint.orEmpty().lowercase()
        return when {
            t.contains("start_of_turn") || t.contains("gemma") -> Family.GEMMA
            t.contains("start_header_id") || t.contains("llama-3") ||
                t.contains("llama3") || t.contains("llama_3") -> Family.LLAMA3
            t.contains("[inst]") || t.contains("mistral") || t.contains("mixtral") -> Family.MISTRAL
            t.contains("<|user|>") || t.contains("phi-3") || t.contains("phi3") -> Family.PHI3
            t.contains("qwen") || t.contains("deepseek") -> Family.QWEN
            else -> Family.CHATML
        }
    }

    fun label(family: Family): String = when (family) {
        Family.GEMMA -> "Gemma"
        Family.LLAMA3 -> "Llama 3"
        Family.MISTRAL -> "Mistral"
        Family.PHI3 -> "Phi-3"
        Family.QWEN -> "Qwen"
        Family.CHATML -> "ChatML"
    }

    fun format(
        family: Family,
        systemPrompt: String,
        turns: List<LlmEngine.Turn>,
        enableThinking: Boolean = true,
    ): String = when (family) {
        Family.GEMMA -> buildString {
            if (systemPrompt.isNotBlank()) {
                append("<start_of_turn>user\n").append(systemPrompt).append("<end_of_turn>\n")
            }
            turns.forEach { turn ->
                append("<start_of_turn>")
                append(if (turn.role == "assistant") "model" else "user")
                append('\n').append(turn.content).append("<end_of_turn>\n")
            }
            append("<start_of_turn>model\n")
        }

        Family.LLAMA3 -> buildString {
            append("<|begin_of_text|>")
            if (systemPrompt.isNotBlank()) {
                append("<|start_header_id|>system<|end_header_id|>\n\n")
                append(systemPrompt).append("<|eot_id|>")
            }
            turns.forEach { turn ->
                append("<|start_header_id|>")
                append(if (turn.role == "assistant") "assistant" else "user")
                append("<|end_header_id|>\n\n").append(turn.content).append("<|eot_id|>")
            }
            append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }

        Family.MISTRAL -> buildString {
            turns.forEachIndexed { index, turn ->
                if (turn.role == "assistant") {
                    append(' ').append(turn.content).append("</s>")
                } else {
                    append("[INST] ")
                    if (systemPrompt.isNotBlank() && index == 0) append(systemPrompt).append("\n\n")
                    append(turn.content).append(" [/INST]")
                }
            }
        }

        Family.PHI3 -> buildString {
            if (systemPrompt.isNotBlank()) append("<|system|>\n").append(systemPrompt).append("<|end|>\n")
            turns.forEach { turn ->
                append("<|")
                append(if (turn.role == "assistant") "assistant" else "user")
                append("|>\n").append(turn.content).append("<|end|>\n")
            }
            append("<|assistant|>\n")
        }

        Family.CHATML -> buildString {
            if (systemPrompt.isNotBlank()) {
                append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
            }
            turns.forEach { turn ->
                append("<|im_start|>")
                append(if (turn.role == "assistant") "assistant" else "user")
                append('\n').append(turn.content).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }

        Family.QWEN -> buildString {
            if (systemPrompt.isNotBlank()) {
                append("<|im_start|>system\n").append(systemPrompt).append("<|im_end|>\n")
            }
            turns.forEach { turn ->
                append("<|im_start|>")
                append(if (turn.role == "assistant") "assistant" else "user")
                append('\n').append(turn.content).append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
            if (!enableThinking) append("<think>\n\n</think>\n\n")
        }
    }
}
