# Server A 信息卡 (Info Card)

> 一页式服务端画像 + 推荐客户端配置。其他平台(iOS / Web / HarmonyOS / 后端 S2S)接入时直接照此调参。
>
> 配套文档:
> - **协议层底层细节** → [`qgb-ws-server-profile.md`](./qgb-ws-server-profile.md)(2026-05-28 python 短探测,鉴权/AES/初始帧/会话覆盖等)
> - **跨平台接入长文** → [`serverA-cross-platform-integration.md`](./serverA-cross-platform-integration.md)(平台特定建议 / SLA / 陷阱清单)
> - **R5d 协议探测** → [`../test-records/2026-06-11_serverA-round4-qgbtech/r5d-correct-protocol-gzip-binary.log`](../test-records/2026-06-11_serverA-round4-qgbtech/r5d-correct-protocol-gzip-binary.log)
> - **R4 22h 实测**(部分结论已被 R5d 推翻,详见报告内 errata)→ [`../test-records/2026-06-11_serverA-round4-qgbtech/report.md`](../test-records/2026-06-11_serverA-round4-qgbtech/report.md)
> - **R6 改协议后实测**(进行中)→ `../test-records/2026-06-12_serverA-round6-qgbtech/`(后续)
>
> 最后一次确认实测:**R5d 2026-06-12**(JVM 单测,新账号 + 干净对照,坐实了协议格式)。

## 0. TL;DR(三句话)

1. **应用层心跳格式 = `gzip("心跳内容")` UTF-8,以 binary 帧发送**;服务端**严格 1:1 应答** `{"cmdType":"HEART_BEAT",...}` GZIP binary 帧,RTT 6–7ms。
2. Server A **永不主动关连接**,也不主动推心跳;静默 = 真正的静默,完全靠**客户端发心跳维持**。
3. 客户端**必须关掉 Java-WebSocket / 等价库的 LCD 机制**(`connectionLostTimeout = 0`)—— 否则 LCD 用 WS-level PING 等不到 PONG 会强杀活连接(R4 实测 303/459 = 66% 死法)。

## 1. 连接端点

| 项 | 值 |
|---|---|
| URL 模板 | `wss://client.qgbtech.cn/ws?token=<JWT>&name=<account>&platform=<plat>&state=<n>` |
| 协议 | RFC 6455 WebSocket over TLS 1.2/1.3 |
| 端口 | 443 |
| 证书 | 公网 CA 签发(R4 期间一切正常,握手中位 60ms) |
| HTTP 升级 | 标准 `Upgrade: websocket`,返回 `101 Switching Protocols` |
| Sec-WebSocket-Extensions | 无(不启用 permessage-deflate) |
| Subprotocol | 无 |
| 鉴权方式 | URL query `token=<JWT RS512>`(详见 qgb-ws-server-profile §2) |

**URL 参数**:

| 参数 | 类型 | 说明 |
|---|---|---|
| `token` | string | RS512 签名 JWT,无 exp 字段,**握手不验证签名**(任何字符串都过) |
| `name` | string | 业务账号 ID(由登录接口返回的 `imAccount`,非手机号) |
| `platform` | enum | `android` / `ios` / `web` / `harmonyos` / `pc` |
| `state` | int | **`1` = 首次登录**;**`0` = 之后任何重连/自动重连/自动登录**(协议方约定,2026-06-12 校对) |

## 2. 服务端行为画像(R5d 干净测、新账号)

| 维度 | 实测值 | 备注 |
|---|---|---|
| 主动关 WS close-frame | **0 次** | 服务端不发 1000/1011/4xxx 关闭帧;所有断开都源自客户端或网络 |
| WS-level PONG 响应 | **几乎从不发** | R5d 完全没收到 PONG 控制帧;**别依赖 WS ping/pong 当心跳** |
| **app 层心跳应答**(正确协议) | **5/5,RTT 6–7ms** | 客户端发 `gzip("心跳内容")` binary → 服务端 1:1 回 `{"cmdType":"HEART_BEAT",...}` |
| app 层 `{"cmdType":"HEART_BEAT"}` text echo | **0 次** | text 帧路径服务端不识别(R5c) |
| onOpen 后服务端首推 | **2 帧固定**:`LIMIT_MULTI_LOGIN`(431B GZIP)+ `PLATFORM_LOGIN_STATE`(238B GZIP) | 多端登录状态 + 当前在线平台清单 |
| 服务端主动推送(干净账号) | **完全沉默**,仅业务消息触发 | 旧账号(被业务流污染)会看到 5s 一次的 HEART_BEAT 广播 — 那是别端的应答被回灌 |
| TCP 静默断流(NAT idle 怀疑值) | ~5min ± 2min | 若期间无双向流量,中间盒子会清表;但心跳通了就用不着担心 |
| TLS 握手延迟 | dns 1ms / tcp 40-60ms / tls 60-100ms | 国内出口稳定 |
| 单连接最大寿命(协议对了) | R1/R2: 3-5min 中位;**R4 错协议**:1m59s 中位;**R6 待测** | 协议对了 + LCD 关了之后预期 30min+ |
| 并发会话(同 account+name) | 旧连接会被新连接踢掉(同账号挤号) | 详见 qgb-ws-server-profile §3 |
| 帧编码 | **下行全是 GZIP binary**(`1f8b08` 头);上行也应当 GZIP binary | 解码必须先 GZIP 再 UTF-8 → JSON;直接 `bytes.decode('utf-8')` 会乱码 |

### 2.1 心跳协议精确定义(✅ 已验证)

**上行**(客户端 → 服务端,**每 45 秒一次**,业界标准间隔):
```
WS-binary-frame(payload = gzip("心跳内容".encode("utf-8")))
```
- 33 字节 gzip 输出,固定字节序列(只要 `"心跳内容"` 不变)
- 必须用 WS **binary** opcode(0x2),不是 text(0x1)

**下行**(服务端 → 客户端,1:1 应答,RTT < 1s 大概率):
```
WS-binary-frame(payload = gzip(json))
```
其中 json:
```json
{
  "cmdType":"HEART_BEAT",
  "code":200,
  "msg":"操作成功",
  "payLoad":{"type":"android"},
  "time":1781247508435
}
```

**判定客户端这次连接还活着**:`gunzip(frame_bytes).contains("HEART_BEAT")`(或更稳:`JSON.parse(gunzip(bytes)).cmdType == "HEART_BEAT"`)。

**实现参考(sgb-management-android)**:
- 心跳类:`component/imc-core/src/main/java/org/daimhim/imc/core/heartbeat/IMCHeartbeatV2.kt`
- 间隔:`heartbeatInterval = 45L * 1000`(line 39)
- 发送:`webSocket.send("心跳内容".gzip().toByteString())`(line 177)
- 应答判定:消息内容同时包含 `"HEART_BEAT"` AND `"请求成功"`(line 72/82)

## 3. 推荐客户端配置

### 3.1 必须做(MUST)

| 项 | 推荐值 | 原因 |
|---|---|---|
| 心跳类型 | **app 层 binary frame, payload = `gzip("心跳内容")`** | R5d 实测唯一被服务端识别+应答的协议 |
| 心跳间隔 | **45s**(对齐 sgb-management-android imc-core) | NAT idle 下限 ~5min,留 ~6× 余量 |
| 心跳超时判定 | **2× 心跳间隔**(90s 无 binary 应答 → 重连) | 业界 IM SDK 通用 1.5–2× 余量 |
| **关闭客户端 WS LCD** | **`connectionLostTimeout = 0`**(Java-WebSocket / OkHttp 等价) | LCD 用 WS-level PING 等不到 PONG 会强杀活连接(R4 66% 死法) |
| 关闭码处理 | **任何 close 都视为非主动断**(自动重连) | 服务端不主动关,所以所有 close 都是网络层问题 |
| 重连退避 | 1s, 2s, 4s, ..., 128s(指数封顶) | 服务端可达性极高,IMMEDIATE 不会被服务端拒 |
| URL 重连前重算 | **每次重连都重新拿 URL**(可能换 token/state) | 静态 URL 会导致 state 错位 |
| `state` 参数 | **首次 `1`,之后都 `0`** | 协议方明文约定 |
| TLS 验证 | **使用系统 trust store + 可选 cert pin** | 国内运营商偶发 SSL 拦截(R2 期间见过) |
| `Origin` header | 任意非空字符串 | 服务端检查 Origin 存在与否,不校验内容 |
| 下行解码 | **先 GZIP 解压再 UTF-8 → JSON** | 服务端所有 binary 帧都是 GZIP;直接 utf-8 decode 会乱码 |

### 3.2 推荐做(SHOULD)

| 项 | 推荐 | 收益 |
|---|---|---|
| 网络可达性主动探测 | 每 60–120s 探测 `client.qgbtech.cn:443`(TCP + TLS) | 区分"服务端挂"和"用户没网" |
| 公网参考点(如 `baidu.com:443`) | 在 SSL 错误连续 3+ 次时探测 | 区分"针对我家 SSL DPI"和"全网不通" |
| AlarmManager / WorkManager 抗 Doze | Android: `setExactAndAllowWhileIdle` | 否则后台心跳会被 Doze 节流到 30min+ |
| DNS 缓存 + stale fallback | TTL 300s + stale 24h | Wi-Fi flap 时 DNS 失败可立即用 stale 重试 |
| 发送-while-disconnect 缓存 | FIFO + 64KB 上限 + 5s TTL | 弱网时 send 不丢,重连后自动 flush |
| 监听 `ConnectivityManager.NetworkCallback` | Android / HarmonyOS 都有等价 API | transports 变化(WIFI ↔ CELL)立即触发重连 |

### 3.3 不要做(MUST NOT)

| 反模式 | 错在哪 |
|---|---|
| ❌ 发 `text` 帧当心跳(裸 `HEART_BEAT` / JSON / `{"cmdType":"HEART_BEAT",...}`)| 服务端 R5b/c 实测 0 响应,text 帧路径不识别 |
| ❌ 用 WS-level ping/pong 当心跳 | R5d 实测服务端从不回 PONG;客户端 LCD 会等不到然后强杀活连接(R4 66% 死法) |
| ❌ 保留 Java-WebSocket / OkHttp 库自带 `connectionLostTimeout` 默认值 | 见上,**显式设为 0** 或 disable |
| ❌ 依赖服务端发 close-frame 判定断线 | Server A 永不主动关,等不到的 |
| ❌ 把 close code 1006/1011 当致命错误退出 | 都是网络层 / 客户端 LCD 误杀,直接重连即可 |
| ❌ 静态 token 跑全程 | JWT 虽不验证 exp,但每次重新登录 token 会换;`state` 也要跟着翻 |
| ❌ 直接 `bytes.decode('utf-8')` 解下行 | 全是 GZIP,先 gunzip 再 utf-8 才是 JSON |
| ❌ 用同一 (account, name) 在多端登录后期望都活着 | 后入挤前出,前端会被踢 |

## 4. 失败模式速查

| 现象 | 大概率原因 | 推荐处理 |
|---|---|---|
| `code:1006 remote:false` + reason 含 "did not respond with a pong" | **客户端库 LCD 等不到 WS PONG 强杀**(R4 主要死法) | **`connectionLostTimeout = 0` 关掉 LCD**;别用 WS ping 当心跳 |
| `code:1006 remote:true` + reason 空 | TCP RST(NAT 清表 / 中间盒子杀 / Wi-Fi flap) | 直接重连;若 5min 内反复,说明 NAT idle 比预想短或链路抖 |
| `code:1000 remote:false` + reason 空 | 客户端业务层主动 close()(心跳模块判死) | 检查心跳实现,确认 90s 容差判定未误判 |
| `SSLException: Read error: Software caused connection abort` | Wi-Fi 切换 / 网络栈被踢 | 等 `NetworkCallback.onAvailable` 后重连;别 spammy retry |
| `SSLHandshakeException` 连续 3+ 次 | 链路 SSL 拦截 / DPI / 证书被换 | 切公网参考点验证;若公网也烂 → user network 烂;若只我家烂 → 走备用 host |
| `UnknownHostException` | DNS 失败(可能伴随 wifi flap) | stale DNS fallback;3 次失败前不切 backup host |
| 握手 15s+ 不返回 101 | 服务端 LB 卡 / TLS 中间人 | 客户端起 handshake watchdog(15s)强 close 重连 |
| 长时间(>90s)收不到 binary 应答 | NAT 清表 + 心跳没发 / 没生效 | 立即重发一次心跳;失败再立即重连 |

## 5. SLA 期望值

基于 R1/R2/R4/R5d/R6 实测:

| 维度 | 中位 | P95 | 最差 | 备注 |
|---|---|---|---|---|
| **单连接寿命(协议正确 + LCD off)** | **30min+**(R6 进行中,预期) | — | — | R1/R2 用 WS ping(没关 LCD)也能跑 3-5min |
| 单连接寿命(用 WS ping,LCD 默认) | 3–5min | 10min | 30min | R1/R2 baseline |
| 单连接寿命(发 text "HEART_BEAT") | **2min** ⚠️ | 2min | 2min | R4:LCD 强杀,协议本就没通 |
| 重连耗时(到 onOpen) | 1.5s | 3s | 6s(handshake watchdog 极限) | |
| 心跳 RTT | 50ms | 600ms | 2.5s | R5d/R6 实测 |
| 22h 内的"完全失联"窗口 | 0(健康)–13h(有 SSL DPI 攻击) | — | 看链路质量 | |
| 累计在线率(健康链路) | >99.9% | — | — | |
| 累计在线率(有 SSL DPI / VPN 抖) | 30–80% | — | 看具体环境 | |

## 6. 快速接入 checklist

```
[ ] URL 模板写对(name/token/platform/state 都齐;state=1 首次,之后 0)
[ ] 心跳 = gzip("心跳内容") binary,45s 间隔
[ ] 心跳应答判定:gunzip → contains "HEART_BEAT"
[ ] 客户端 WS 库的 LCD/connectionLostTimeout 显式设 0
[ ] 任意 close → 自动重连 + 指数退避 1→128s
[ ] 监听网络变化(WIFI/CELL 切换立即触发重连)
[ ] DNS 缓存 + stale fallback
[ ] 发送队列(disconnect 时缓存,onOpen 后 flush)
[ ] 15s handshake watchdog(防止 LB 卡)
[ ] 主动 TCP+TLS 探测 60–120s 一次(用于区分服务端 vs 链路)
[ ] 下行 binary 帧统一先 gunzip → JSON.parse 再分流
[ ] JWT token 通过安全通道下发(不要硬编码)
[ ] 同账号挤号 UX:收到 "被挤" 推送(若服务端发) → 提示用户
[ ] 后台 Doze / iOS 后台限制:接 push notification 拉起重连
```

## 7. 联系/责任人

- **服务端**:qgbtech 后端组
- **客户端 Android 参考实现**:`org.daimhim.imc-core`([:imc-core 模块](../imc-core/src/main/java/org/daimhim/imc_core))
- **测试报告**:[`test-records/`](../test-records/)
- **新发现 / 协议变动**:更新本卡 + 在 `test-records/<date>_serverA-roundN-qgbtech/` 留报告
