package io.github.sshtunnelvpn.ui.profiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sshtunnelvpn.AppContainer
import io.github.sshtunnelvpn.data.AuthType
import io.github.sshtunnelvpn.data.IpInfo
import io.github.sshtunnelvpn.data.IpInfoRepository
import io.github.sshtunnelvpn.data.Profile
import io.github.sshtunnelvpn.sshvpn.Sshvpn
import io.github.sshtunnelvpn.sshvpn.TestResult
import io.github.sshtunnelvpn.tunnel.BasePlatform
import io.github.sshtunnelvpn.tunnel.EngineOps
import io.github.sshtunnelvpn.tunnel.HostKeyMismatch
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
    var exit by mutableStateOf<IpInfo?>(null)
        private set
    /** 本次測試連線測得的伺服器 IPv6 能力(null = 未測或無法判定)。 */
    var ipv6 by mutableStateOf<Boolean?>(null)
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
            val r = withContext(Dispatchers.Default) { runCatching { Sshvpn.inspectKey(p.privateKey, p.passphrase) } }
            if (p != profile) return@launch
            r.onSuccess { keyInfo = KeyInfo(it.publicKey, it.fingerprint); keyError = null }
                .onFailure { keyInfo = null; keyError = it.message }
        }
    }

    fun generateKey(type: String) {
        viewModelScope.launch {
            val kp = withContext(Dispatchers.Default) {
                runCatching { Sshvpn.generateKey(type, "sshtunnelvpn@android", profile.passphrase) }
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
            if (p.username.isBlank()) add("username")
            if (p.authType != AuthType.PASSWORD && p.privateKey.isBlank()) add("privateKey")
            if (p.authType != AuthType.KEY && p.password.isEmpty()) add("password")
        }

    fun runTest() {
        test = TestState.Running
        exit = null
        ipv6 = null
        var mismatch: HostKeyMismatch? = null
        val platform = BasePlatform(c.knownHosts, persistHostKeys = false, onMismatch = { mismatch = it }, logs = c.logs)
        viewModelScope.launch {
            test = EngineOps.test(profile, platform, IpInfoRepository.EXIT_URL).fold(
                onSuccess = {
                    exit = it.exitInfo.takeIf(String::isNotEmpty)?.let(IpInfoRepository::parse)
                    ipv6 = ipv6Of(it.getIPv6())
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
        exit?.let { c.ipInfo.recordExit(p.id, it) }
        ipv6?.let { c.ipInfo.recordIpv6(p.id, it) }
        val s = c.settings.current()
        if (s.selectedProfileId == null || c.profiles.get(s.selectedProfileId) == null) {
            c.settings.update { it.copy(selectedProfileId = p.id) }
        }
    }
}
