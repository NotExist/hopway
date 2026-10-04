package not.exist.hopway.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBackupTest {
    private val a = Profile(id = "a", name = "Tokyo", host = "203.0.113.10", username = "neo", password = "pw-a")
    private val b = Profile(
        id = "b", name = "Home", host = "home.example.org", port = 2222, username = "neo",
        authType = AuthType.KEY, privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n", passphrase = "kp",
    )
    private val hostA = KnownHost("203.0.113.10", 22, "ssh-ed25519", "SHA256:aaa")
    private val unrelated = KnownHost("other.example", 22, "ssh-ed25519", "SHA256:zzz")

    // 測試用較少 iterations,邏輯與正式值相同
    private val fast = 1_000

    @Test
    fun plainExportHasNoSecrets() {
        val json = ProfileBackup.export(listOf(a, b), listOf(hostA, unrelated), passphrase = null)
        assertFalse(json.contains("pw-a"))
        assertFalse(json.contains("PRIVATE KEY"))
        assertFalse(json.contains("\"kp\""))
        assertFalse("only related host keys are exported", json.contains("other.example"))
        val p = (ProfileBackup.parse(json) as ProfileBackup.Parsed.Plain).payload
        assertEquals(listOf("a", "b"), p.profiles.map { it.id })
        assertEquals(2222, p.profiles[1].port)
        assertEquals(listOf(hostA), p.knownHosts)
    }

    @Test
    fun encryptedRoundTrip() {
        val json = ProfileBackup.export(listOf(a, b), listOf(hostA), "correct horse".toCharArray(), fast)
        assertFalse("secrets must not appear in plaintext", json.contains("pw-a") || json.contains("PRIVATE KEY"))
        val blob = (ProfileBackup.parse(json) as ProfileBackup.Parsed.Encrypted).blob
        val p = ProfileBackup.decrypt(blob, "correct horse".toCharArray())
        assertEquals(listOf(a, b), p.profiles)
    }

    @Test(expected = WrongPassphraseException::class)
    fun wrongPassphrase() {
        val json = ProfileBackup.export(listOf(a), emptyList(), "correct horse".toCharArray(), fast)
        val blob = (ProfileBackup.parse(json) as ProfileBackup.Parsed.Encrypted).blob
        ProfileBackup.decrypt(blob, "wrong horse!".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsForeignJson() {
        ProfileBackup.parse("""{"profiles":[]}""")
    }

    @Test
    fun mergeKeepsLocalSecretsAndTrust() {
        val incoming = BackupPayload(
            profiles = listOf(ProfileBackup.stripSecrets(a.copy(name = "Tokyo 2")), Profile(id = "c", host = "198.51.100.1", username = "x")),
            knownHosts = listOf(hostA.copy(fingerprint = "SHA256:changed"), unrelated),
        )
        val r = ProfileBackup.merge(listOf(a, b), mapOf(hostA.key to hostA), incoming)
        assertEquals(1, r.added)
        assertEquals(1, r.updated)
        assertEquals(listOf("a", "b", "c"), r.profiles.map { it.id })
        val merged = r.profiles.first { it.id == "a" }
        assertEquals("Tokyo 2", merged.name)
        assertEquals("local password kept when import has none", "pw-a", merged.password)
        assertEquals("existing trust wins", "SHA256:aaa", r.knownHosts.getValue(hostA.key).fingerprint)
        assertEquals(1, r.hostKeyConflicts)
        assertTrue(r.knownHosts.containsKey(unrelated.key))
    }

    @Test
    fun importingSameFileTwiceIsIdempotent() {
        val payload = (ProfileBackup.parse(ProfileBackup.export(listOf(a, b), listOf(hostA), null)) as ProfileBackup.Parsed.Plain).payload
        val first = ProfileBackup.merge(emptyList(), emptyMap(), payload)
        assertEquals(2, first.added)
        val second = ProfileBackup.merge(first.profiles, first.knownHosts, payload)
        assertEquals(0, second.added)
        assertEquals(2, second.updated)
        assertEquals(0, second.hostKeyConflicts)
        assertEquals(first.profiles, second.profiles)
        assertEquals(first.knownHosts, second.knownHosts)
    }

    @Test
    fun clearedThenPlainImportHasMissingCredentials() {
        val payload = (ProfileBackup.parse(ProfileBackup.export(listOf(a, b), emptyList(), null)) as ProfileBackup.Parsed.Plain).payload
        val r = ProfileBackup.merge(emptyList(), emptyMap(), payload)
        assertEquals(setOf(Credential.PASSWORD), r.profiles.first { it.id == "a" }.missingCredentials)
        assertEquals(setOf(Credential.PRIVATE_KEY), r.profiles.first { it.id == "b" }.missingCredentials)
        // 加密匯入則帳密齊全
        val enc = ProfileBackup.export(listOf(a, b), emptyList(), "correct horse".toCharArray(), fast)
        val full = ProfileBackup.decrypt((ProfileBackup.parse(enc) as ProfileBackup.Parsed.Encrypted).blob, "correct horse".toCharArray())
        assertTrue(full.profiles.all { it.missingCredentials.isEmpty() })
    }

    @Test
    fun missingCredentialsByAuthType() {
        val both = Profile(authType = AuthType.KEY_AND_PASSWORD)
        assertEquals(setOf(Credential.PASSWORD, Credential.PRIVATE_KEY), both.missingCredentials)
        assertTrue(a.missingCredentials.isEmpty())
        // 金鑰模式不需要密碼
        assertTrue(b.missingCredentials.isEmpty())
    }

    @Test
    fun selectionAfterImport() {
        val list = listOf(a, b)
        assertEquals("existing selection kept", "b", ProfileBackup.selectionAfterImport("b", list))
        assertEquals("no selection -> first", "a", ProfileBackup.selectionAfterImport(null, list))
        assertEquals("stale selection -> first", "a", ProfileBackup.selectionAfterImport("deleted", list))
        assertEquals(null, ProfileBackup.selectionAfterImport(null, emptyList()))
    }
}
