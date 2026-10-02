package io.github.sshtunnelvpn.data

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ProfileRepository(private val store: DataStore<ProfileStore>) {
    val profiles: Flow<List<Profile>> = store.data.map { it.profiles }

    suspend fun get(id: String): Profile? = store.data.first().profiles.find { it.id == id }

    suspend fun upsert(p: Profile) = store.updateData { s ->
        val list = s.profiles.toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i >= 0) list[i] = p else list += p
        s.copy(profiles = list)
    }

    suspend fun delete(id: String) = store.updateData { s -> s.copy(profiles = s.profiles.filterNot { it.id == id }) }

    suspend fun move(from: Int, to: Int) = store.updateData { s ->
        val list = s.profiles.toMutableList()
        if (from in list.indices && to in list.indices) list.add(to, list.removeAt(from))
        s.copy(profiles = list)
    }

    suspend fun replaceAll(profiles: List<Profile>) = store.updateData { ProfileStore(profiles) }
}

class SettingsRepository(private val store: DataStore<AppSettings>) {
    val settings: Flow<AppSettings> = store.data

    suspend fun current(): AppSettings = store.data.first()

    suspend fun update(f: (AppSettings) -> AppSettings) {
        store.updateData(f)
    }
}

sealed interface HostKeyVerdict {
    data object Trusted : HostKeyVerdict
    data object NewlyTrusted : HostKeyVerdict
    data class Mismatch(val known: KnownHost) : HostKeyVerdict
}

/** TOFU(trust on first use):首次連線記住主機金鑰,之後金鑰變了就拒絕。 */
class KnownHostsRepository(private val store: DataStore<KnownHostStore>) {
    val hosts: Flow<List<KnownHost>> = store.data.map { s -> s.hosts.values.sortedBy { it.host } }

    suspend fun verify(host: String, port: Int, keyType: String, fingerprint: String, persist: Boolean): HostKeyVerdict {
        val key = KnownHost.keyOf(host, port)
        val known = store.data.first().hosts[key]
        return when {
            known == null -> {
                if (persist) trust(host, port, keyType, fingerprint)
                HostKeyVerdict.NewlyTrusted
            }
            known.fingerprint == fingerprint -> HostKeyVerdict.Trusted
            else -> HostKeyVerdict.Mismatch(known)
        }
    }

    suspend fun trust(host: String, port: Int, keyType: String, fingerprint: String) {
        store.updateData { s ->
            val k = KnownHost(host, port, keyType, fingerprint)
            s.copy(hosts = s.hosts + (k.key to k))
        }
    }

    suspend fun forget(key: String) {
        store.updateData { s -> s.copy(hosts = s.hosts - key) }
    }
}
