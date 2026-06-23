# QGB WebSocket 服务端探知档案

> 通过 imc-core demo(QgbWsTestActivity)实测得出,设备 HRY-AL00a,2026-05-28/29。
> 标注【实测】= 有日志证据;【推断】= 由现象推断;【外部】= 来自 sgb-management-android 源码。

## 1. 接入点

| 项 | 值 |
|---|---|
| URL(debug) | `wss://client.qgbtech.cn/ws?token=<JWT>&name=<imAccount>&platform=android&state=<0\|1>` 【外部】 |
| URL(release) | `wss://client.jingyingbang.com/ws?token=%s&name=%s&platform=android&state=%s` 【外部】 |
| 端口 / 协议 | 443 / wss(TLS,Draft_6455) |
| 参数 `token` | 登录返回的 JWT |
| 参数 `name` | 登录返回的 `imAccount` |
| 参数 `platform` | `android` 固定 |
| 参数 `state` | `0`=首次连接,`1`=重连(本 demo 首次成功后自动置 1) |

## 2. 鉴权 / 登录(取 token + name)【外部 + 实测】

```
1. GET  https://api.jybtech.cn/authentication/oauth/getKey
        → { code, msg, result:{ publicKey(base64), indexKey }, payload(hex,AES) }
2. password 用 publicKey 做 RSA/ECB/PKCS1 加密 → Base64 → 再 Base64(双重)
3. POST https://api.qgbtech.cn/sgb-app-manager/v2/index/login   (form-urlencoded)
        username=<手机号> & password=<双Base64> & indexKey=<step1> & platform=android
        → BaseEntity{ code, msg, data:LoginEntity{ token, imAccount, imToken, phone ... } }
```

- 业务数据字段:`data` / `result` / `records`(BaseEntity 的 @SerializedName alternate)。
- 加密响应:`data` 为空且有 `payload`(**hex 编码**)时,AES/CBC/PKCS7 解密;key=`8985b2c4fb1900cf` iv=`bc3f07fbe54c4b90`。
- 未注册号返回:`{"code":"500","msg":"当前手机号暂未注册,请先注册","data":{}}` 【实测】

## 3. 连接建立行为【实测】

- 握手成功(onOpen / `101 Switching Protocols`)后,服务端**立即下发 2 个 binary 帧**:`433 bytes` + `263 bytes`(登录/握手响应)。
- 此后**不主动推送业务消息**(空闲时静默)。
- 通信走 **binary 帧**(`onMessage(bytes)`),非 text。

## 4. ⏱ 空闲超时 ≈ 60 秒(核心限制)【实测】

零帧(完全不发任何心跳/数据)连接,多次采样(本机 python websockets,禁库级 ping):

| 轮次 | 存活 | close |
|---|---|---|
| #0(安卓 demo) | 59s | 1006 remote:true |
| #1 | **204.4s** | 1006 |
| #2 | **61.2s** | 1006 |
| #3 | **60.1s** | 1006 |

→ **不是恒定值**:多数 ~60s,偶尔更长(204s)。**最短可在 ~60s 被踢**,故 60s 视为可靠下界。
设计规则:**心跳间隔必须 <60s**(我方 30s/45s 安全)。close code 恒为 `1006`(无 close 帧,TCP 层断)。

## 5. 保活机制对比【实测】

| 方式 | 服务端是否回包 | 是否续命 | 探活能力 |
|---|---|---|---|
| 应用层 JSON 心跳 `{"cmdType":"HEART_BEAT"}` | **不回 ack** | **会**(认作活动,重置 60s 计时器) | 弱:只能靠 socket 关闭感知死连 |
| **WS 协议 ping/pong**(RFC6455) | **正常回 pong**(RTT ~50–74ms) | **会** | **强:双向确认,主动探活** ✅ |

WS ping/pong 证据:
```
18:27:23 → WS ping #2   18:27:23 ← pong RTT 64ms
18:27:53 → WS ping #3   18:27:53 ← pong RTT 50ms
连续 2+ 分钟稳活无断
```

> JSON 60s 心跳曾贴边 60s 超时偶发被踢(存活 5min 后断)→ 不可取 60s。

## 6. 关闭 / 异常行为【实测】

| 场景 | 表现 |
|---|---|
| 空闲超时被踢 | `onClose` code=`1006` remote=true → SDK 转 onError → 自动重连 |
| 弱网 TLS 握手失败 | `SSLHandshakeException: Connection closed by peer`,卡在 Connecting(50% 丢包时观察到卡 63s) |
| 服务端可达性探测 | NetSurveillance 探测点 `client.qgbtech.cn:443`(TLS),公网参照 `www.baidu.com:443` |

## 7. 限制汇总 & 我方对策

| 服务端限制 | 我方对策 |
|---|---|
| 硬空闲超时 ~60s | 心跳间隔 <60s:**前台 30s / 后台 45s** |
| JSON 心跳不回 ack(无法主动探活) | 正式心跳改用 **WS ping/pong**(服务端会 pong) |
| 自适应心跳会探到 >60s 必被踢 | **不用 SMART 自适应**,改固定间隔 |
| 弱网下 TLS 握手慢/失败 | autoConnect 指数退避(init 1s / max 128s)+ NetSurveillance 智能退避 |
| Doze 下定时器被冻结 | 心跳/重连调度器用 `setExactAndAllowWhileIdle` |

## 8. 专项探测结果(2026-05-29,本机 python `doc/qgb_ws_probe.py`)

| 测试 | 结果 | 结论 |
|---|---|---|
| **空闲超时精确值** | 存活 204 / 61 / 60s(3 轮) | ~60s 下界,非恒定;心跳须 <60s 【实测】 |
| **WS ping payload 回显** | 发 `qgb-ping-123` → pong 回显一致,RTT 34ms | 服务端 RFC6455 合规,正确回显 ping payload 【实测】 |
| **单账号并发连接** | 同 token 2 条连接各存活 60s 不互踢 | WS 层**不限制单账号并发**,无单会话踢人 【实测】 |
| **篡改签名 token** | 改签名尾部仍握手成功 + 收到初始帧 + 15s 不踢 | ⚠️ **服务端 WS 层不校验 JWT 签名**(安全隐患) 【实测】 |
| **最大会话时长** | 30s ping 保活挂满 600s(10min)无强制关,19 个 ping 全正常 | 10min 内无会话上限;反证 **WS ping/pong 30s 保活 10min 零掉线** 【实测】 |

### JWT 解码【实测】
```
header : {"typ":"JWT","alg":"RS512"}
payload: {"sub":"1331559906599374848","scope":"default","iss":"13088956112","login":1779849372}
```
- **无 `exp` 字段** → token 自身不带过期时间,有效期由服务端侧维护。
- `login`=1779849372(Unix 秒)≈ 签发时间戳。
- RS512 签名,但 §8 实测服务端 WS 层**不验签**,故无法靠篡改/过期 token 在 WS 层触发拒连。

### ⚠️ 安全提示(给后端)
WS 握手未校验 `?token=` 的 JWT 签名:结构合法但签名错误的 token 也能连上并收到初始下发帧(432/263B)。建议后端在 WS 握手阶段验签 + 校验有效期。

## 9. 仍待确认

- 是否有**小时级**最大会话硬上限(10min 已确认无上限,更长需挂数小时观测)。
- token 有效期到期后的服务端行为(WS 层不验签 → 大概率不在 WS 层拒;HTTP 业务接口可能拒)。
- 初始下发的 2 个 binary 帧(432/263B)的协议含义(疑似登录态 / 路由信息,protobuf?)。
