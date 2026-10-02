# gomobile 產生的 binding 以 JNI 依名稱呼叫,不可混淆或移除。
-keep class go.** { *; }
-keep class io.github.sshtunnelvpn.sshvpn.** { *; }
-keep class * implements io.github.sshtunnelvpn.sshvpn.Platform { *; }
