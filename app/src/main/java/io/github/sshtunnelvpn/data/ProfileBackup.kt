package io.github.sshtunnelvpn.data

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 伺服器清單匯出檔。
 *
 * - 不含密碼/私鑰時:`payload` 為明文 JSON(只有連線資訊與已信任的主機金鑰)。
 * - 含密碼/私鑰時:整份 payload 以使用者密語加密(PBKDF2-HMAC-SHA256 → AES-256-GCM),
 *   檔案中只有 `encrypted`,明文帳密不會出現在檔案裡。
 */
@Serializable
data class BackupFile(
    val format: String = ProfileBackup.FORMAT,
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val includesSecrets: Boolean = false,
    val payload: BackupPayload? = null,
    val encrypted: EncryptedBlob? = null,
)

@Serializable
data class BackupPayload(
    val profiles: List<Profile>,
    /** 這些伺服器已信任的主機金鑰,匯入後延續 TOFU 信任,不必重新確認。 */
    val knownHosts: List<KnownHost> = emptyList(),
)

@Serializable
data class EncryptedBlob(
    val kdf: String,
    val iterations: Int,
    val salt: String,
    val iv: String,
    val data: String,
)

data class MergeResult(
    val profiles: List<Profile>,
    val added: Int,
    val updated: Int,
    val knownHosts: Map<String, KnownHost>,
    /** 匯入檔與本機記錄不同的主機金鑰數(以本機為準,不覆蓋)。 */
    val hostKeyConflicts: Int,
)

class WrongPassphraseException : Exception("wrong passphrase or corrupted file")

object ProfileBackup {
    const val FORMAT = "sshtunnelvpn-servers"
    const val MIN_PASSPHRASE = 8
    private const val KDF = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 310_000

    sealed interface Parsed {
        data class Plain(val payload: BackupPayload) : Parsed
        data class Encrypted(val blob: EncryptedBlob) : Parsed
    }

    fun stripSecrets(p: Profile) = p.copy(password = "", privateKey = "", passphrase = "")

    fun export(
        profiles: List<Profile>,
        knownHosts: List<KnownHost>,
        passphrase: CharArray?,
        iterations: Int = ITERATIONS,
    ): String {
        val hostKeys = profiles.map { KnownHost.keyOf(it.host, it.port) }.toSet()
        val related = knownHosts.filter { it.key in hostKeys }
        val file = if (passphrase == null) {
            BackupFile(payload = BackupPayload(profiles.map(::stripSecrets), related))
        } else {
            val plain = AppJson.encodeToString(BackupPayload.serializer(), BackupPayload(profiles, related))
            BackupFile(includesSecrets = true, encrypted = encrypt(plain.encodeToByteArray(), passphrase, iterations))
        }
        return AppJson.encodeToString(BackupFile.serializer(), file)
    }

    /** 解析匯出檔;格式不符時丟 IllegalArgumentException。 */
    fun parse(json: String): Parsed {
        val f = runCatching { AppJson.decodeFromString(BackupFile.serializer(), json) }
            .getOrElse { throw IllegalArgumentException("not a server list file", it) }
        require(f.format == FORMAT) { "not a server list file" }
        require(f.version == 1) { "unsupported file version ${f.version}" }
        f.encrypted?.let { return Parsed.Encrypted(it) }
        return Parsed.Plain(requireNotNull(f.payload) { "empty server list file" })
    }

    fun decrypt(blob: EncryptedBlob, passphrase: CharArray): BackupPayload {
        require(blob.kdf == KDF) { "unsupported kdf ${blob.kdf}" }
        val d = Base64.getDecoder()
        val key = deriveKey(passphrase, d.decode(blob.salt), blob.iterations)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, d.decode(blob.iv)))
        val plain = try {
            c.doFinal(d.decode(blob.data))
        } catch (_: AEADBadTagException) {
            throw WrongPassphraseException()
        }
        return AppJson.decodeFromString(BackupPayload.serializer(), plain.decodeToString())
    }

    /**
     * 合併規則:
     * - 以 id 比對;已存在者更新連線設定,但匯入檔沒有帳密(未加密匯出)時保留本機原有帳密。
     * - 不存在者新增。
     * - 主機金鑰:本機已有的以本機為準(避免匯入檔覆蓋既有信任),只補上本機沒有的。
     */
    fun merge(existing: List<Profile>, existingHosts: Map<String, KnownHost>, incoming: BackupPayload): MergeResult {
        val byId = existing.associateBy { it.id }.toMutableMap()
        val order = existing.map { it.id }.toMutableList()
        var added = 0
        var updated = 0
        for (p in incoming.profiles) {
            val old = byId[p.id]
            if (old == null) {
                byId[p.id] = p
                order += p.id
                added++
            } else {
                byId[p.id] = p.copy(
                    password = p.password.ifEmpty { old.password },
                    privateKey = p.privateKey.ifEmpty { old.privateKey },
                    passphrase = p.passphrase.ifEmpty { old.passphrase },
                )
                updated++
            }
        }
        val hosts = existingHosts.toMutableMap()
        var conflicts = 0
        for (h in incoming.knownHosts) {
            val cur = hosts[h.key]
            when {
                cur == null -> hosts[h.key] = h
                cur.fingerprint != h.fingerprint -> conflicts++
            }
        }
        return MergeResult(order.map { byId.getValue(it) }, added, updated, hosts, conflicts)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun encrypt(plain: ByteArray, passphrase: CharArray, iterations: Int): EncryptedBlob {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(128, iv))
        val e = Base64.getEncoder()
        return EncryptedBlob(KDF, iterations, e.encodeToString(salt), e.encodeToString(iv), e.encodeToString(c.doFinal(plain)))
    }
}
