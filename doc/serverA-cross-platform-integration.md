# Server A (qgbtech WebSocket) 跨平台接入指南

> 文档定位:**给 iOS / HarmonyOS / Web / 后端服务**等非 Android 客户端接入 `wss://client.qgbtech.cn/ws` 时的实战参考。
>
> 数据来源:
> - 服务端协议层细节:见 [`qgb-ws-server-profile.md`](./qgb-ws-server-profile.md)(python 短探测,2026-05-28)
> - 客户端长跑稳定性数据:`test-records/`(Android SDK 实测,R1=18h / R2=23.5h / R4=进行中)
> - 本文档补充服务端"性格画像"(长尾故障、network 环境敏感性)和跨平台集成建议
>
> 更新:2026-06-11

---

## 0. TL;DR(三句话)

1. **服务端不规范**:99% 关闭是 `1006 remote:true` 空 reason 的中间盒 RST,不是 RFC6455 close frame。客户端要把"被踢"当**预期事件**处理,不是异常。
2. **空闲超时短而抖动**:压力测试看 ≥3min 中位、≤30min 长尾。实战必须 **≤30s 心跳间隔** + 用 **app-layer 文本 payload**(WS ping frame 在中间盒上可能被忽略)。
3. **TLS 链路敏感**:6h+ 长跑会出 5-6 次/h `SSLProtocolException`,Wi-Fi MITM/DPI 环境下可能完全连不上(R2 出现 13h TLS 死期)。需要做链路降级识别 + 用户提示。

---

## 1. 服务端接入点

| 字段 | 值 | 来源 |
|---|---|---|
| 协议 | WSS (TLS 1.2+ over TCP/443) | 实测 |
| URL 模板 | `wss://client.qgbtech.cn/ws?token=<JWT>&name=<imAccount>&platform=<id>&state=<n>` | sgb-management-android 源码 |
| Release URL | `wss://client.jingyingbang.com/ws?...` | 同上 |
| WS Draft | RFC6455 (Draft_6455) | 实测 |
| 服务端 TLS | TLS 1.2/1.3(具体 cipher 未抓包确认) | 实测 |

### URL 参数说明

| 参数 | 类型 | 说明 |
|---|---|---|
| `token` | JWT (RS512 签名) | 走业务登录接口拿,见 §2 |
| `name` | 字符串 | 登录返回的 `imAccount`(数字串,如 `202206211949282`) |
| `platform` | 字符串 | `android` / `ios` / `web` / `harmony` 等。**服务端目前不校验值**,但请按平台填确保后端可观测 |
| `state` | 整数 | `0` = 首次连接,`>0` = 重连第 N 次。客户端**每次重连前递增**,服务端用于行为路由(已观察到与 `state=1` 的连接行为略有差异) |

### ⚠️ 安全警告(给后端)

> **WS 握手不校验 JWT 签名**(2026-05-29 实测):改 token 签名尾段也能握上+收到初始下发帧。建议后端在握手阶段补:
> 1. JWT 签名验证(`tokenManager.verifyToken` 已在业务接口用,WS 握手未用)
> 2. JWT 有效期校验(token 自身没有 `exp` 字段,需服务端侧 token 表 + revoke 列表)

---

## 2. 鉴权(走 HTTPS 业务接口拿 token)

详细流程见 [`qgb-ws-server-profile.md` §2](./qgb-ws-server-profile.md#2-鉴权--登录取-token--name)。摘要:

```
1. GET https://api.jybtech.cn/authentication/oauth/getKey
   → publicKey (base64), indexKey
2. password 走 RSA/ECB/PKCS1 + 双 Base64
3. POST https://api.qgbtech.cn/sgb-app-manager/v2/index/login (form-urlencoded)
   → { token, imAccount, imToken, ... }
```

跨平台注意:
- **RSA padding 必须是 PKCS1**,不要用 OAEP(后端用 PKCS1)
- 密码加密结果做**两次 Base64**(实测,容易遗漏)
- 加密响应在 `payload` 字段(hex 编码),非 base64;AES/CBC/PKCS7,key/iv 见旧文档 §2

---

## 3. 服务端连接行为(实战画像)

### 3.1 握手 + 首帧推送

| 事件 | 时机 | 内容 |
|---|---|---|
| HTTP 101 | DNS+TCP+TLS 完成后,平均 ~330ms | RFC6455 Switching Protocols |
| 首个 binary 帧 | onOpen 后 ~20ms | **433 bytes**(疑似登录态/路由信息,二进制) |
| 次个 binary 帧 | onOpen 后 ~40ms | **262 bytes**(同上,变长) |
| 后续业务帧 | 异步,基于业务触发 | binary 为主 |

**关键**:服务端**所有业务帧是 binary**(`onMessage(bytes)`),不是 text。客户端的 message 处理路径要按 `bytes` 走,不要默认 `text`。

### 3.2 空闲超时(更新自旧文档)

**旧文档说**:~60s 硬超时。**长跑实测修正**:

| 来源 | 中位连接存活 | 最长 | 99% 关闭码 |
|---|---|---|---|
| 旧文档(60s 短探测) | 60-204s | — | 1006 |
| **R1 18h Android 长跑** | **5 min** | 30 min | 99% `1006 remote:true` |
| **R2 23.5h Android 长跑** | **3 min** | 25 min | 97% `1006 remote:true` |

**真相**:
- 旧文档观察到的 60s 是**零数据流**情况下的最小值;
- 实际 client 持续发心跳/消息时,中位会拉长到 **3-5 分钟**,但仍会被中间盒以 5-10min 周期 RST;
- **没有"心跳间隔够短就不断"的安全区**——即便 5s WS ping + dataPing 双保险,长跑仍会断;
- 看起来像 Nginx/SLB 上有个 ~5min 的硬上限(任何连接到了就 kill,不管有没有流量)。

**给客户端**:把"长连接会被服务端定期踢"当**协议正常事件**,自动重连必须可靠 + 用户无感。

### 3.3 心跳/保活

旧文档 §5 总结的两条路径:

| 方式 | 服务端响应 | 续命 | 探活 |
|---|---|---|---|
| App-layer JSON `{"cmdType":"HEART_BEAT"}` | **无 ack**(但被视为"活跃流量") | ✅ | ❌ |
| WS protocol ping (opcode 0x9) | **正常 pong**(RTT 50-74ms) | ✅ | ✅ |

**实战推荐**:**两条同时发**。理由:
- WS ping 服务端会回 pong,可主动探活 → 客户端能更快感知死连
- App-layer text 给某些 DPI/NAT 更"看得见"的流量信号,防止它认为是 idle
- A1 改进里 imc-core 默认 `V2FixedHeartbeat + StringPingCustomHeartbeat("HEART_BEAT")` 走 app-layer 路径,**WS ping 不发**;实战发现 R4 第 1 分钟就出 `HeartbeatDegraded ENTERED_TOLERANCE`,即 30s 内无任何回包——说明 app-layer text **服务端不主动回 ack**,需要其他客户端消息触发响应

跨平台推荐**双心跳**:
- 周期发 WS ping frame(opcode 0x9),期望 pong
- 同时周期发 text/binary 业务流量(可以是 dataPing `{"cmdType":"HEART_BEAT"}`),让中间盒看到 app-layer activity
- 周期 ≤ 30s(给 60s 服务端 idle 预留 2× 余量)

### 3.4 关闭码分布(长跑实测)

| 关闭码 | R1 (18h) | R2 (23.5h) | 含义 | 客户端动作 |
|---|---|---|---|---|
| `1006 remote:true` reason="" | **104** (99%) | **86** (97%) | 中间盒/服务端 TCP RST,非规范关 | 🔄 立即重连,**不算异常** |
| `1006 pong-timeout` | 1 | 0 | client lib 内部检测 pong 超时 | 🔄 同上(实际也是被踢导致) |
| `-1` (handshake/TLS) | 38 | 42 | TLS 层失败 | 🔄 退避重连,看 SSL 错类型 |
| `SSLProtocolException` | 100 | 85 | TLS 读写期断流 | 🔄 同上 |
| `SSLHandshakeException` | 18 | 35 | TLS 握手期失败 | ⚠️ 多次连续 → 链路嫌疑 |
| `1002` (protocol error) | 0 | 4 | WS 协议错(server 发了不规范帧?) | 🔄 重连 |
| `1000` (normal close) | 0 | 3 | 唯一规范关闭路径,**罕见** | 🔄 重连(可能是 server restart) |

---

## 4. 跨平台接入清单

### 4.1 必做(无论平台)

```
[ ] WSS over TLS 1.2+;不接受 1.0/1.1
[ ] URL 模板携带 4 个 query 参数:token, name, platform, state
[ ] state 重连前递增(可放进 SharedPref / NSUserDefaults / localStorage)
[ ] 心跳间隔 ≤ 30s
[ ] 同时发送 WS ping(opcode 0x9)和 app-layer dataPing 文本
[ ] 容差窗口 1.5-2× 心跳间隔(给慢网/Doze 留余量)
[ ] 自动重连指数退避:init=1s, max=128s, 上限 ~3 次后允许 BACKOFF_LONG
[ ] 1006 remote:true 当作"预期事件",不要弹错误给用户
[ ] 连续 N 次 SSL 错 → UI 提示用户"网络环境异常,请切换 Wi-Fi/4G"
[ ] DNS 端侧缓存(TTL 300s)+ 系统 DNS 失败时 fallback 到缓存 IP
[ ] 消息发送时未连状态走 FIFO 缓存,onOpen 后排空
[ ] 业务 message 默认按 binary 处理(server 主用 binary)
```

### 4.2 推荐(增强稳定性)

```
[ ] 网络切换(Wi-Fi↔蜂窝↔VPN)时主动 close + reconnect(旧 socket 大概率已死)
[ ] 连续 attempt-timeout(30s 都没握上)→ 进入"链路降级"状态,缩短 timeout 节电
[ ] Doze/前台-后台切换 → 心跳间隔自动切档(前台 30s / 后台 45s)
[ ] 后台心跳调度走平台精确闹钟(Android setExactAndAllowWhileIdle / iOS UNUserNotificationCenter)
[ ] 长尾故障识别:连续 1006 remote:true 间隔接近(±20%)→ 推断 NAT idle 数值,自适应缩短心跳
[ ] cert pinning(防止 Wi-Fi 链路 MITM,见 §5.2)
```

### 4.3 可选(高级)

```
[ ] failover URL:demo 里 surveillance 推 FAILOVER 时业务可切备用接入点
[ ] 主动探测:用 publicReference(如 baidu.com:443)区分"服务端炸"vs"我网络炸"
[ ] 客户端事件总线(SDK 已有 ImcEvents),便于业务做 metric / 可观测
```

---

## 5. 平台特定建议

### 5.1 iOS

**推荐栈**:`URLSession.webSocketTask`(iOS 13+)或 [Starscream](https://github.com/daltoniam/Starscream)

| 主题 | 推荐做法 |
|---|---|
| WS 库选择 | URLSession 内置:简单、可靠,但**不支持自定义 ping payload**(关键限制)<br>Starscream:支持自定义 frame,推荐用于需要 dataPing 的场景 |
| TLS 配置 | 默认系统 TrustManager,无需自定义 |
| Cert pinning | URLSession 用 `URLSessionDelegate.urlSession(_:didReceive:completionHandler:)`<br>Starscream 用 `pinnedKeys` 配置 |
| 后台 WS | iOS 系统会在后台几分钟内挂起 WS,**不要寄希望于长后台连接**;改用 silent push + 重连恢复 |
| 心跳调度 | 前台用 Timer,后台无法主动调度(系统接管) |
| Reachability | `NWPathMonitor`(iOS 12+)替代旧 Reachability;`NWPath.usesInterfaceType` 检测 Wi-Fi/Cellular |

**iOS 特有坑**:
- `URLSession.webSocketTask` 的 ping 不带 payload,无法做 RTT 测量;要测延时只能用 app-layer text echo
- 后台心跳调度受系统约束,业务侧应假设"被踢后才重连",而不是"心跳保活"
- 1006 在 URLSession 里报为 `URLError.networkConnectionLost`,需要业务侧映射

### 5.2 HarmonyOS

参考 `~/.claude/docs/harmonyos/` 下的 IM SDK 排障文档。HarmonyOS 走 OHOS WebSocket API。

| 主题 | 推荐做法 |
|---|---|
| WS API | `@ohos.net.webSocket` 内置 |
| TLS | 跟随系统 trust store;ArkTS 不支持自定义 SSLContext,cert pinning 通过证书指纹比对实现 |
| 后台 | HarmonyOS 应用挂起策略类似 Android Doze,需要申请长连接保活权限 |
| 心跳 | `setTimeout` 在后台被限频;建议用 `taskScheduling` 模块 |

详见 `qgb_harmonyos/doc/` 项目附录(若存在)。

### 5.3 Web (Browser)

**坑最多的平台**。

| 主题 | 推荐做法 / 限制 |
|---|---|
| WS API | 标准 `WebSocket(url)` |
| TLS | 浏览器控制,**无法做 cert pinning** |
| WS ping | **浏览器不暴露** ping/pong opcode 的发送接口(只能被动响应) → dataPing 是唯一可用的保活手段 |
| 后台 | Tab 不可见时 setTimeout 间隔被限制到 ≥1s(Chrome 100+);WS 仍可工作但心跳调度受限 |
| 断网检测 | `navigator.onLine` 不可靠,要靠 WS readyState 推断 |
| Auto-reconnect | 不能用退避到 128s 那么久(用户体验差),建议 init 1s / max 30s |

**Web 特有约定**:
- dataPing 用 `{"cmdType":"HEART_BEAT"}` 文本帧,周期 25s(留 5s buffer)
- 完全没有 WS ping 可用,纯靠 app-layer 流量保活
- 监听 `online` / `offline` 事件 + `document.visibilitychange`,后台 →前台时主动 close + reconnect
- 不要用 SharedWorker / ServiceWorker 跑 WS(多 Tab 复用复杂度极高,不值)

### 5.4 后端服务(server-to-server,接 qgbtech 作为下游)

| 主题 | 推荐做法 |
|---|---|
| WS 库 | 推荐 OkHttp(Java/Kotlin) / `gorilla/websocket`(Go) / `aiohttp`(Python) |
| 连接池 | 单 connection 即可,无需池化 |
| 心跳 | 同移动端,30s + WS ping + dataPing |
| 监控 | 把 connection lifetime / close code 上报 metric;Server A 5min 中位是预期值,不要报警 |
| 部署 | 服务端运行在内网时**不要**经过代理(代理常加严格的 idle timeout) |

---

## 6. 已知环境陷阱

### 6.1 Wi-Fi 链路 SSL 拦截(Round 2 实测,严重)

**症状**:
- TCP probe 显示服务端可达,但 wss 握手 30s timeout
- 13 小时持续 timeout,SDK 按 128s 节奏硬撞 256 次都连不上
- Wi-Fi 切到 4G/VPN 之后**几乎瞬间恢复**

**根因(推断)**:
- 部分企业/酒店/运营商 Wi-Fi 有 TLS-aware DPI,silent drop 非"可信清单"的 TLS 流量
- 不是 RST(那样 client 能立即收到错),是**吞包**(client 在 30s timeout 等死)

**客户端应对**:
- 连续 3+ 次 `attempt-timeout-30000ms` + probe verdict=TLS_FAILURE → 进入"链路降级"状态(imc-core A7 实现)
- emit 业务事件 `LinkDegraded(reason=TLS_BLACKHOLE)`,UI 提示"网络环境不允许加密连接,请切换 Wi-Fi/4G"
- transport 切换时主动 close + reconnect(imc-core A8 实现)

### 6.2 TLS 协议层间歇性错(常态)

5-6 次/小时 `SSLProtocolException` 是 Server A **稳态**表现(R1/R2 频率几乎一致),不是偶发。

**两种来源**:
1. 中间盒在 TLS data 传输中 RST → SDK 看到 SSLProtocolException(可观察 §3.4 1006 同时段大量出现)
2. 服务端 TLS 实现本身有问题(需要服务端配合抓包确认)

**客户端应对**:
- 连续 N 次 SSL 错 → 触发 forceProbe(imc-core A6 实现)
- TLS 错按阶段细化分类(`HANDSHAKE` / `READ` / `WRITE` / `CLOSE_NOTIFY` / `PIN_FAILURE`),业务侧给不同 UX

### 6.3 NAT idle 5min(常态)

99% 关闭是这个。客户端**不要试图根治**(改不了服务端 / 中间盒),做好以下三件事就够了:
1. 自动重连快(<10s 内 onOpen)
2. 把 1006 remote:true 当**预期事件**,不要弹错给用户
3. 消息发送时未连状态走 FIFO 缓存(onOpen 后排空,client 视角"消息没丢")

---

## 7. 长跑稳定性预期(给业务定 SLA 参考)

基于 R1+R2 41.5h 实测,**未应用任何 SDK 改进时**:

| 指标 | 数值 |
|---|---|
| 平均 reconnect 频率 | 4-6 次/h |
| 中位连接存活 | 3-5 min |
| 最长连接存活 | 25-30 min |
| 总连接可用率 | ~95% (有 99% 时间连着,但有重连过渡期) |
| SSL 错误频率 | 5-6 次/h |
| 13h+ 链路死期发生概率 | ~5%(取决于 Wi-Fi 环境,纯蜂窝/VPN 罕见) |

**应用 imc-core 全套改进(A1-A14)后**,Round 4 测试目标:
| 指标 | 预测 |
|---|---|
| reconnect 频率 | < 1 次/h |
| 中位连接存活 | ≥ 30 min |
| INTERNAL noise | 0 |
| 长尾故障识别 | 自动 emit LinkDegraded,业务侧可观测 |

(R4 实测数据出来后更新本表)

---

## 8. 参考实现

| 平台 | 路径 | 状态 |
|---|---|---|
| **Android (Kotlin/JVM)** | `imc-core` 模块 | ✅ 生产可用,本仓库 |
| Android demo | `:app` 模块,`QgbWsTestActivity` | ✅ 测试用 |
| Python 探针 | `doc/qgb_ws_probe.py` | ✅ 短探测用 |
| iOS / HarmonyOS / Web | 暂无 | ❌ 待实现 |

跨平台实现优先级建议:**iOS → Web → HarmonyOS**(用户量排序)。

---

## 9. 相关文档

- [`qgb-ws-server-profile.md`](./qgb-ws-server-profile.md) — 服务端协议层底层细节(2026-05-28 写)
- `test-records/2026-06-03_serverA-qgbtech/report.md` — R1 长跑数据
- `test-records/2026-06-08_serverA-round2-qgbtech/report.md` — R2 长跑数据 + 13h 死期事件
- `test-records/2026-06-09_serverB-local/report.md` — Local server 对照组(SDK 在健康 server 上的表现)
- `CLAUDE.md` — imc-core SDK 架构总览

---

## 10. 仍待确认 / TODO

按优先级:

1. **服务端 TLS 配置抓包**:openssl s_client + Wireshark 抓一次完整握手,确认 cipher 列表、SNI 处理、证书链是否完整 → 是否能解释 §6.2 的稳态 SSL 错
2. **服务端 idle timeout 精确值**:R1/R2 看到中位 3-5min,旧文档 60s 下界,需要服务端配合查 Nginx/SLB 配置确认真实值
3. **服务端是否发 close-frame**:99% 是 1006 remote:true,意味着 server **从来不发** RFC6455 close-frame。问下后端是有意为之还是 Nginx 配置疏漏
4. **JWT 签名验证补齐**(§1 安全警告)
5. **iOS / Web / HarmonyOS 客户端**实测验证本文档的跨平台建议(暂无数据)
