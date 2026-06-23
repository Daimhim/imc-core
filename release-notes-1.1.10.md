## 1.1.10

代码与 1.1.9 完全一致。**1.1.9 在 JitPack 因 AGP 7.2.1 启动需要 JDK 11 而构建失败**,jitpack.yml 修复后 JitPack 缓存住了旧 commit 的 error 结果,无法 headless 刷新 — 故升 1.1.10 重新发。**消费者请直接用 1.1.10**。

```kotlin
// JitPack
implementation("com.github.Daimhim:imc-core:1.1.10")
```

JDK 要求:**JDK 8+**(JAR 字节码 major=52,Kotlin stdlib-jdk8)。

---

### 新增
- `IKeyProvider`:重连时拉取最新 URL(支持 state=0 首连 / state=1 重连 等场景)
- `RapidResponseForceV4`:per-instance 实现,替代静态共享的 V2
- `IMessageCache`:持久化发送缓存(File / SharedPrefs)+ owner 隔离,账号切换自动清理
- `NetSurveillance` 三层模型:`NetStateMonitor` + `NetProber` + `NetSurveillance`,产出 `ReconnectAction` 驱动重连
- `:weaknet` 子模块:弱网/丢包/延迟模拟测试
- `gw.ps1` / `gw.cmd`:基于腾讯镜像的 Gradle wrapper 加速器
- `jitpack.yml`:openjdk11 + 只 install `:imc-core`,规避 AGP 在 JDK 8 上启动失败

### 修复
- Bug B:`V2JavaWebEngine` 加 handshake watchdog(15s 卡 Connecting 自动关闭)
- P0-1:`IAutoConnect` 加 `IReconnector`,autoConnect 走 `engineOn` 路径,修复 `webSocketClient=null` 时 reconnect 空转
- `V2SmartHeartbeat` / `V2FixedHeartbeat`:切换为 idempotent `startAutoConnect`,不再清零退避
- `lastEffectiveAction` 防抖:NetSurveillance 短时间内重复 IMMEDIATE 不再反复 cancel / re-arm

### 标记弃用(`@Deprecated` + `replaceWith`)
- `OkhttpIEngine` / `JavaWebEngine` / `UDPEngine` → `V2JavaWebEngine`
- `RapidResponseForceV2` → `RapidResponseForceV4`
- `DefCustomHeartbeat` → `V2FixedHeartbeat`
- `IEngineActionListener` → `IMCStatusListener`
- `UDPReceiveParser`(无替代,后续大版本会移除)

### 工具链
- Gradle 7.5 → 7.6.6
- `.gitignore` 加 `**/build/` 排除子模块构建产物
