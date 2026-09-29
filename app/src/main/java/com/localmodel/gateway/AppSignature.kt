package com.localmodel.gateway

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

object AppSignature {

    private const val S = "LocalModelGateway/core/v1"

    private const val C1 = "3CKRjONRYIH6//+9wfXyvw=="
    private const val C2 = "CEoqaNv9ye75UvhAoI/OxQ=="
    private const val C3 = "rDYmIiCx2lqNVtx6/9ijDhm8vV8lPSqvxrVn7d+WJJo="

    private const val D1 = "4249fc23d43bf2c73341509e676efd2242de33e390d96123f409e85eeb5d6608"
    private const val D2 = "c8efa505d93b961f0522dcc4f1848fcd2e32001436533590852e7c2555d0866e"
    private const val D3 = "806f81c6a1519131bc42f134b7666f4672ac1a73197c3437d4abd2fdd52ef8d5"

    @Volatile
    private var cachedText: String? = null

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun key(seed: ByteArray): SecretKeySpec =
        SecretKeySpec(digest(seed).copyOf(16), "AES")

    private fun decrypt(secret: SecretKeySpec, blob: String): String = runCatching {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, secret)
        String(cipher.doFinal(Base64.decode(blob, Base64.DEFAULT)), Charsets.UTF_8)
    }.getOrDefault("")

    private fun segments(): Pair<String, ByteArray> {
        val c1 = Base64.decode(C1, Base64.DEFAULT)
        val c2 = Base64.decode(C2, Base64.DEFAULT)
        val c3 = Base64.decode(C3, Base64.DEFAULT)
        val p1 = decrypt(key(S.toByteArray(Charsets.UTF_8)), C1)
        val p2 = decrypt(key(c1), C2)
        val p3 = decrypt(key(c1 + c2), C3)
        val valid = hex(digest(p1.toByteArray(Charsets.UTF_8))) == D1 &&
            hex(digest(p2.toByteArray(Charsets.UTF_8))) == D2 &&
            hex(digest(p3.toByteArray(Charsets.UTF_8))) == D3
        val raw = c1 + c2 + c3
        return (if (valid) p1 + p2 + p3 else "") to raw
    }

    fun text(): String {
        cachedText?.let { return it }
        val value = segments().first
        cachedText = value
        return value
    }

    fun isIntact(): Boolean = text().isNotEmpty()

    fun keystream(): ByteArray {
        val (value, raw) = segments()
        return digest(value.toByteArray(Charsets.UTF_8) + raw)
    }
}
