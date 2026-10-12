package io.github.ammarqaisar11a55.relaysms

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import io.github.ammarqaisar11a55.relaysms.core.security.FieldCipher
import io.github.ammarqaisar11a55.relaysms.core.security.TinkFieldCipher

/** Real AES-256-GCM cipher with an in-memory key, for JVM tests. */
fun testFieldCipher(): FieldCipher {
    AeadConfig.register()
    return TinkFieldCipher(KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM")).getPrimitive(Aead::class.java))
}
