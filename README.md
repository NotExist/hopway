# SSH Tunnel VPN (Android)

A system-wide VPN that tunnels traffic over a standard SSH server, similar to `ssh -D` but for the whole phone.
The server needs nothing beyond OpenSSH (`AllowTcpForwarding yes`, the default).

A rewrite of [Anton2319/VPNoverSSH](https://github.com/Anton2319/VPNoverSSH) with a different data-plane architecture.

## Architecture

```
App traffic → TUN fd ─┬─ gVisor netstack (Go) ─ TCP ──→ SSH direct-tcpip channel ─┐
                      │                       └ UDP:53 → DNS-over-TCP (pipelined) ─┤→ N parallel SSH connections → server
                      │                       └ other UDP → badvpn-udpgw (optional) ┘
Kotlin/Compose UI ←── gomobile binding (sshvpn.aar) ── stats / state / logs / host-key callbacks
```

| | VPNoverSSH (reference) | This project |
|---|---|---|
| Data path | tun2socks → **local SOCKS5 on 127.0.0.1:1080** → trilead-ssh2 (JVM) | TUN → gVisor → SSH channel, **all in Go, no loopback hop, no JVM in the data path** |
| SSH connections | 1 | 1–6 parallel connections, channels go to the least-loaded one |
| Ciphers | trilead defaults | AEAD first (aes128-gcm / chacha20-poly1305) |
| DNS | Sent **outside** the tunnel to a configured IP (leaks) | Virtual DNS 198.18.0.2 → DNS-over-TCP through SSH, query pipelining + TTL cache |
| UDP | None | Optional badvpn-udpgw |
| IPv6 | Not routed (leaks) | Routed through the tunnel |
| Reconnect | None (`while(true)` busy loop) | keepalive@openssh.com probing, exponential backoff, immediate reconnect on network change; pending dials wait for reconnection |
| Host keys | Not verified | TOFU known_hosts; mismatch blocks the connection and shows both fingerprints |
| Auth | Password / key (no passphrase) | Password, key (OpenSSH/PEM, Ed25519/ECDSA/RSA, passphrase), keyboard-interactive, in-app key generation |
| Credential storage | Plaintext SharedPreferences | DataStore, AES-256-GCM via Android Keystore |
| UI | XML Views + Preferences | Jetpack Compose, Material 3 / Material You, Navigation 3, zh-TW + en |
| Other | — | Per-app allow/deny list, LAN bypass + custom excluded subnets, SOCKS5 proxy (shareable to LAN), QS tile, always-on VPN, live speed chart & stats, log viewer |

## Building

Builds run on GitHub Actions; each workflow has exactly one purpose, selected by its trigger:

| Workflow | Trigger | What it does |
|---|---|---|
| `test.yml` — Run tests | push to `main` / pull request (doc-only changes skipped) | Go core tests (race) + Kotlin unit tests |
| `build-debug.yml` — Build debug APK | manual (`gh workflow run build-debug.yml`) | Debug APK, signed with the fixed key in secret `DEBUG_KEYSTORE_BASE64` and verified |
| `publish-release.yml` — Publish release | push tag `v*` | Release APK → GitHub Release; needs `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` |

Shared setup (Go, JDK 21, Android platform/NDK, Gradle cache) lives in `.github/actions/android-setup`.
`versionName` is fixed at `0.1.0`; `versionCode` = Unix time in minutes at build time, so every build increases it.
Build time and commit are baked into `BuildConfig` and shown in Settings → About.
APK / artifact names: `SSHTunnelVPN-0.1.0-debug-<yyyyMMdd-HHmm, UTC+8>-<sha7>` and `SSHTunnelVPN-<tag>-release-<yyyyMMdd-HHmm>`.

Toolchain used by CI: Go (from `core/go.mod`), JDK 21, Android platform 37, NDK 28.2.13676358.
gomobile/gobind are pinned via the `tool` block in `core/go.mod`.

## Tests

```sh
cd core && go test -race ./...     # end-to-end: in-process SSH server + socketpair standing in for the TUN
./gradlew :app:testDebugUnitTest   # CIDR complement (route computation for API < 33)
```

The Go tests use a second gVisor stack as the "app", so they cover the whole path:
TCP payload integrity (8 × 2 MB in both directions), two parallel SSH connections, DNS pipelining and caching,
udpgw, SOCKS5, reconnect after the server drops the connection, auth failure, host-key rejection, and public-key auth with an encrypted key.

## IPv6

IPv6 traffic is **always captured** by the VPN (`::/0` is routed into the tunnel), so it can never leak out of the
physical network. The setting only decides whether apps may *use* IPv6 through the SSH server:

| Mode | VPN interface | Behavior |
|---|---|---|
| Automatic (default) | IPv6 address only if this server was measured to have IPv6 | After connecting, the engine opens a direct-tcpip channel to `[2606:4700:4700::1111]:443`. Success → IPv6 goes through the tunnel; "connect failed" → IPv6 is blocked. The result is remembered per server; a mismatch re-establishes the VPN once. New servers start blocked. |
| Through tunnel | always has an IPv6 address | Apps prefer IPv6 and it is sent via the server. Only for servers with working IPv6. |
| Block | no IPv6 address | IPv6 sockets fail immediately (no source address), Android stops asking for AAAA, apps use IPv4. |

Why this matters: if the interface has an IPv6 address but the server has no IPv6 internet access, apps try IPv6 first.
Previously the engine completed the TCP handshake before connecting upstream, so apps saw "connected, then reset"
instead of "connection refused" and Happy Eyeballs never fell back to IPv4 (e.g. Google app feed and thumbnails stopped loading).

Engine-side safeguards (independent of the mode):

- **Connect upstream first, then answer the SYN.** If the SSH channel cannot be opened, the app gets a RST
  (connection refused) and immediately tries its next address.
- **Known no-IPv6 server:** IPv6 destinations are refused without opening a channel, and AAAA queries get an empty NOERROR answer.
- **Dropped UDP gets ICMP port unreachable** (no udpgw), so QUIC falls back to TCP at once instead of timing out.
- The home screen shows failed connections, dropped UDP flows, and the current IPv6 state.

## UDP (optional)

Run [badvpn-udpgw](https://github.com/ambrop72/badvpn) on the server, then enable "UDP via udpgw" in the profile:

```sh
badvpn-udpgw --listen-addr 127.0.0.1:7300 --max-clients 64
```

Without it, only DNS goes over UDP; QUIC and similar protocols fall back to TCP automatically.

## Limitations

- ABIs: arm64-v8a and x86_64 only (gVisor has no 32-bit ARM support).
- Without udpgw, real-time UDP (games, VoIP) does not work.
- TCP-over-TCP: on lossy links, throughput is limited by the outer SSH TCP connection. Parallel connections reduce this but cannot remove it.
