package com.localmodel.gateway

object SignatureCodec {

    private val key: ByteArray by lazy { AppSignature.keystream() }

    fun crypt(text: String): String {
        if (text.isEmpty() || key.isEmpty()) return text
        val source = text.toByteArray(Charsets.UTF_8)
        val out = ByteArray(source.size)
        for (i in source.indices) {
            out[i] = (source[i].toInt() xor (key[i % key.size].toInt() and 0xFF)).toByte()
        }
        return String(out, Charsets.ISO_8859_1)
    }

    fun guard(text: String): String =
        if (AppSignature.isIntact()) text else crypt(text)
}
