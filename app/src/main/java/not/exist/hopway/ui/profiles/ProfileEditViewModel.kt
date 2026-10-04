package not.exist.hopway.ui.profiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import not.exist.hopway.AppContainer
import not.exist.hopway.data.AuthType
import not.exist.hopway.data.IpInfo
import not.exist.hopway.data.IpInfoRepository
import not.exist.hopway.data.Profile
import not.exist.hopway.core.Core
import not.exist.hopway.core.TestResult
import not.exist.hopway.tunnel.BasePlatform
import not.exist.hopway.tunnel.EngineOps
import not.exist.hopway.tunnel.HostKeyMismatch
import not.exist.hopway.tunnel.toProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val result: TestResult) : TestState
    data class Failed(val message: String, val mismatch: HostKeyMismatch? = null) : TestState
}

data class KeyInfo(val publicKey: String, val fingerprint: String)

class ProfileEditViewModel(private val c: AppContainer, private val id: String?) : ViewModel() {
    var profile by mutableStateOf(Profile())
        private set
    var loaded by mutableStateOf(id == null)
        private set
    var test by mutableStateOf<TestState>(TestState.Idle)
        private set
    var keyInfo by mutableStateOf<KeyInfo?>(null)
        private set
    var keyError by mutableStateOf<String?>(null)
        private set
    var generatedKey by mutableStateOf<KeyInfo?>(null)
    /** 本次測試連線取得的兩協定出口與伺服器能力(存檔時寫回)。 */
    var probe by mutableStateOf<IpInfoRepository.Probe?>(null)
        private set
    private var original: Profile? = null

    init {
        if (id != null) viewModelScope.launch {
            c.profiles.get(id)?.let { profile = it; original = it }
            loaded = true
            inspectKey()
        }
    }

    fun update(f: (Profile) -> Profile) {
        val old = profile
        profile = f(old)
        if (old.privateKey != profile.privateKey || old.passphrase != profile.passphrase) inspectKey()
    }

    private fun inspectKey() {
        val p = profile
        if (p.authType == AuthType.PASSWORD || p.privateKey.isBlank()) {
            keyInfo = null
            keyError = null
            return
        }
        viewModelScope.launch {
            val r = withContext(Dispatchers.Default) { runCatching { Core.inspectKey(p.privateKey, p.passphrase) } }
            if (p != profile) return@launch
            r.onSuccess { keyInfo = KeyInfo(it.publicKey, it.fingerprint); keyError = null }
                .onFailure { keyInfo = null; keyError = it.message }
        }
    }

    fun generateKey(type: String) {
        viewModelScope.launch {
            val kp = withContext(Dispatchers.Default) {
                runCatching { Core.generateKey(type, "hopway@android", profile.passphrase) }
            }
            kp.onSuccess {
                update { p ->
                    p.copy(
                        privateKey = it.privateKey,
                        authType = if (p.authType == AuthType.PASSWORD) AuthType.KEY else p.authType,
                    )
                }
                generatedKey = KeyInfo(it.publicKey, it.fingerprint)
            }.onFailure { keyError = it.message }
        }
    }

    val validation: List<String>
        get() = buildList {
            val p = profile
            if (p.host.isBlank()) add("host")
            if (p.port !in 1..65535) add("port")
            if (p.isSsh) {
                if (p.username.isBlank()) add("username")
                if (p.authType != AuthType.PASSWORD && p.privateKey.isBlank()) add("privateKey")
                if (p.authType != AuthType.KEY && p.password.isEmpty()) add("password")
            } else if (p.password.isNotEmpty() && p.username.isBlank()) {
                add("username") // SOCKS5:帳密選填,但有密碼就必須有帳號
            }
        }

    fun runTest() {
        test = TestState.Running
        probe = null
        var mismatch: HostKeyMismatch? = null
        val platform = BasePlatform(c.knownHosts, persistHostKeys = false, onMismatch = { mismatch = it }, logs = c.logs)
        viewModelScope.launch {
            test = EngineOps.test(profile, platform, withExit = true).fold(
                onSuccess = {
                    probe = it.toProbe()
                    TestState.Ok(it)
                },
                onFailure = { TestState.Failed(it.message ?: it.toString(), mismatch) },
            )
        }
    }

    fun trustAndRetest(m: HostKeyMismatch) {
        viewModelScope.launch {
            c.knownHosts.trust(m.host, m.port, m.keyType, m.newFingerprint)
            runTest()
        }
    }

    suspend fun save() {
        val p = profile.let { it.copy(host = it.host.trim(), username = it.username.trim(), name = it.name.trim()) }
        c.profiles.upsert(p)
        val o = original
        // 端點變了,舊的出口資訊不再可信;本次測試若有查到出口則直接記下
        if (o != null && (o.host != p.host || o.port != p.port || o.username != p.username)) c.ipInfo.forget(p.id)
        probe?.let { c.ipInfo.record(p.id, it) }
        val s = c.settings.current()
        if (s.selectedProfileId == null || c.profiles.get(s.selectedProfileId) == null) {
            c.settings.update { it.copy(selectedProfileId = p.id) }
        }
    }
}
