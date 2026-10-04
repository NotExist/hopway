package not.exist.hopway.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/** 以 Android Keystore 的 AES-256-GCM 金鑰加解密;金鑰不可匯出,資料無法離開本機解開。 */
object SecretBox {
    private const val ALIAS = "hopway-store"
    private const val IV_LEN = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, IV_LEN))
        return c.doFinal(data, IV_LEN, data.size - IV_LEN)
    }
}

private class JsonSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
    private val encrypted: Boolean,
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return defaultValue
        return try {
            val plain = if (encrypted) SecretBox.decrypt(bytes) else bytes
            AppJson.decodeFromString(serializer, plain.decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("cannot decode store", e)
        } catch (e: java.security.GeneralSecurityException) {
            throw CorruptionException("cannot decrypt store", e)
        }
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        val plain = AppJson.encodeToString(serializer, t).encodeToByteArray()
        output.write(if (encrypted) SecretBox.encrypt(plain) else plain)
    }
}

fun <T> jsonDataStore(
    context: Context,
    name: String,
    serializer: KSerializer<T>,
    default: T,
    encrypted: Boolean = false,
): DataStore<T> = DataStoreFactory.create(
    serializer = JsonSerializer(serializer, default, encrypted),
    corruptionHandler = ReplaceFileCorruptionHandler { default },
    produceFile = { File(context.filesDir, "datastore/$name") },
)
