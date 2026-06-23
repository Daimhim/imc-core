# imc-core

Android WebSocket 长连接 IM 内核:在 WebSocket 之上提供**心跳保活、智能自动重连、网络可达性探测、发送缓存、证书指纹校验、DNS 缓存**等能力,面向后台常驻的即时通讯场景。

## 接入

### Step 1. 添加 JitPack 仓库
在 root `build.gradle` 的 repositories 末尾:
```
allprojects {
    repositories {
        ...
        maven { url 'https://jitpack.io' }
    }
}
```

### Step 2. 添加依赖
```
dependencies {
    implementation 'com.github.Daimhim:imc-core:1.1.10'
}
```

## 快速开始

```kotlin
// 1. 构建引擎(可按需挂自动重连 / 心跳 / 网络监控)
val engine = V2JavaWebEngine.Builder().build()

// 2. 监听连接态
engine.setIMCStatusListener(object : IMCStatusListener {
    override fun connectionSucceeded() { /* 已连上,可发消息 */ }
    override fun connectionClosed(code: Int, reason: String?) {}
    override fun connectionLost(throwable: Throwable) {}
})

// 3. 监听消息(回调在 WebSocket IO 线程,刷新 UI 请自行切主线程)
engine.addIMCListener(object : V2IMCListener {
    override fun onMessage(text: String) {}
    override fun onMessage(byteArray: ByteArray) {}
})

// 4. 连接 / 发送 / 断开
engine.engineOn("wss://your-server/ws?token=…")
engine.send("hello")
engine.engineOff()
```

## 常用 API

+ `engineOn(key: String)` — 启动并连接到 key 指定的 URL
+ `engineOff()` — 关闭引擎
+ `engineState(): Int` — 当前引擎状态(取值见 `IEngineState`)
+ `send(byteArray: ByteArray): Boolean` — 发送二进制消息;false 仅代表未立即出网(已入缓存等重连),非丢消息
+ `send(text: String): Boolean` — 发送文本消息,返回值语义同上
+ `addIMCListener / removeIMCListener(V2IMCListener)` — 新消息监听
+ `addIMCSocketListener(level: Int, V2IMCSocketListener)` — 消息拦截器,level 越小越先收到,默认 15;返回 true 消费并中止后续派发
+ `removeIMCSocketListener(V2IMCSocketListener)` — 移除拦截器
+ `setIMCStatusListener(IMCStatusListener?)` — 连接态监听,传 null 清除
+ `onChangeMode(mode: Int)` — 切换心跳模式(模式 key 由 `Builder.addHeartbeatMode` 注册,如前台/后台不同间隔)
+ `onNetworkChange(networkState: Int)` — 网络切换时调用,重置抢救机制
+ `makeConnection()` — 主动触发一次重连

## 核心组件

| 组件 | 作用 |
|---|---|
| `V2JavaWebEngine` / `IEngine` | 长连接引擎(`Builder` 构建) |
| `ProgressiveAutoConnect` / `IAutoConnect` | 指数退避 + 网络感知的自动重连 |
| `V2FixedHeartbeat` / `V2SmartHeartbeat` / `CustomHeartbeat` | 心跳:固定间隔 / 反 NAT 自适应 / 自定义 payload |
| `NetSurveillance` + `DefaultNetProber` | 网络可达性分层探测,产出 `ReconnectAction` 建议 |
| `IMessageCache` | 断线期间发送缓存(内存 / 文件),重连后自动 flush |
| `DnsCache` / `CertificatePinner` / `IKeyProvider` | DNS 缓存兜底 / 证书指纹校验 / 动态刷新 URL |
| `ImcEvents` | 结构化事件总线(连接 / 重连 / 探测 / TLS 诊断),供监控上报 |

## 线程模型

- 监听器回调(消息 / 连接态)默认在 **WebSocket IO 线程**触发,**不要做阻塞操作**;刷新 UI 请自行切主线程。
- `send()` 线程安全,任意线程可调。
- 回调内可安全调用 `send()`;**不要**在回调内调用 `engineOff()`。
- 回调中抛出的异常会被 SDK 吞掉,不影响其它 listener;如需上报请自行 try-catch。
