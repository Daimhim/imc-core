#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
QGB WebSocket 服务端探测脚本(临时调试用)。
用本机 python `websockets` 库从干净网络直连 wss://client.qgbtech.cn/ws,验证服务端参数/限制。

子命令:
  idle [n]        连上后什么都不发(禁库级自动 ping),计时到服务端关闭;重复 n 次(默认 3)。测空闲超时。
  ping [payload]  连上后发一个带 payload 的 WS ping,等 pong,看 RTT + 是否回显。测 ping/pong 回显。
  concurrent      同 token 同时开 2 条连接,各带 30s 保活 ping,观察 60s 看是否互踢。测单账号并发。
  badtoken        用篡改签名的 token 连接,观察服务端拒绝行为(握手失败 / close code)。测鉴权拒连。
  hold [sec]      带 30s WS ping 保活,挂 sec 秒(默认 600),看是否被强制关。测最大会话时长(长观测取大值)。

token/name 为本地调试默认值(已泄露,勿提交);可用环境变量 QGB_TOKEN / QGB_NAME 覆盖。
"""
import asyncio
import os
import ssl
import sys
import time

import websockets

TOKEN = os.environ.get("QGB_TOKEN", (
    "eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzUxMiJ9."
    "eyJzdWIiOiIxMzMxNTU5OTA2NTk5Mzc0ODQ4Iiwic2NvcGUiOiJkZWZhdWx0Iiwi"
    "aXNzIjoiMTMwODg5NTYxMTIiLCJsb2dpbiI6MTc3OTg0OTM3Mn0."
    "Rd8girEDjFhaKz4LVXRsHmSQCqpRpoIj2qFInhno_F6GEhFojgDgIn5daOV5-aMzn"
    "xKmTEU0VC48GU8o3NlM2BhOIh84ISebDWgNKubUeF6Eg5mYq2-WGgLEjaXAUIP8_s"
    "Z4LuykolHHQlimvHxI-1suSVTXQ20w1pfYkucrJF8"
))
NAME = os.environ.get("QGB_NAME", "202206211949282")
HOST = "wss://client.qgbtech.cn/ws"

SSL_CTX = ssl.create_default_context()


def url(token=TOKEN, state=1):
    return f"{HOST}?token={token}&name={NAME}&platform=android&state={state}"


def ts():
    return time.strftime("%H:%M:%S")


async def _drain_initial(ws, secs=2.0):
    """收一下连上瞬间服务端下发的帧(登录响应),不阻塞太久。"""
    frames = []
    try:
        end = time.time() + secs
        while time.time() < end:
            msg = await asyncio.wait_for(ws.recv(), timeout=end - time.time())
            frames.append(len(msg) if isinstance(msg, (bytes, bytearray)) else f"text:{len(msg)}")
    except (asyncio.TimeoutError, Exception):
        pass
    return frames


async def cmd_idle(n=3):
    print(f"[idle] 测空闲超时,共 {n} 轮(连上不发任何帧)")
    for i in range(1, n + 1):
        t0 = time.time()
        try:
            async with websockets.connect(url(), ssl=SSL_CTX, ping_interval=None,
                                          open_timeout=15, close_timeout=5) as ws:
                init = await _drain_initial(ws)
                print(f"  #{i} {ts()} connected, 初始帧={init},静默等待关闭…")
                try:
                    while True:
                        await ws.recv()   # 收到啥都行;服务端关闭会抛 ConnectionClosed
                except websockets.ConnectionClosed as e:
                    dt = time.time() - t0
                    print(f"  #{i} ★ 存活 {dt:.1f}s → close code={e.code} reason={e.reason!r}")
        except Exception as e:
            print(f"  #{i} 连接异常: {type(e).__name__}: {e}")


async def cmd_ping(payload="qgb-ping-123"):
    data = payload.encode()
    print(f"[ping] 测 WS ping payload 回显,payload={payload!r}")
    async with websockets.connect(url(), ssl=SSL_CTX, ping_interval=None, open_timeout=15) as ws:
        await _drain_initial(ws)
        t0 = time.time()
        pong_waiter = await ws.ping(data)   # websockets 按 payload 匹配 pong
        try:
            await asyncio.wait_for(pong_waiter, timeout=10)
            rtt = (time.time() - t0) * 1000
            # 能 await 成功 = 收到 payload 完全一致的 pong(库按 payload 匹配)→ 服务端正确回显
            print(f"  ✓ 收到 pong,RTT {rtt:.0f}ms,且 payload 与发送一致(服务端正确回显)")
        except asyncio.TimeoutError:
            print("  ✗ 10s 内未收到匹配 payload 的 pong(服务端不回显 / 不响应带 payload 的 ping)")


async def _keepalive(ws, tag, interval=30):
    while True:
        await asyncio.sleep(interval)
        try:
            await ws.ping(b"ka")
            print(f"  [{tag}] {ts()} ping")
        except Exception:
            return


async def cmd_concurrent():
    print("[concurrent] 同 token 开 2 条连接,各 30s ping 保活,观察 60s 看是否互踢")
    results = {}

    async def one(tag):
        t0 = time.time()
        try:
            async with websockets.connect(url(), ssl=SSL_CTX, ping_interval=None,
                                          open_timeout=15) as ws:
                await _drain_initial(ws)
                print(f"  [{tag}] {ts()} connected")
                ka = asyncio.create_task(_keepalive(ws, tag))
                try:
                    while time.time() - t0 < 60:
                        await asyncio.wait_for(ws.recv(), timeout=60 - (time.time() - t0))
                except asyncio.TimeoutError:
                    print(f"  [{tag}] {ts()} 60s 仍存活 ✓")
                    results[tag] = "alive"
                except websockets.ConnectionClosed as e:
                    print(f"  [{tag}] {ts()} ✗ 被关 code={e.code} reason={e.reason!r} (存活 {time.time()-t0:.1f}s)")
                    results[tag] = f"closed {e.code}"
                finally:
                    ka.cancel()
        except Exception as e:
            print(f"  [{tag}] 异常: {type(e).__name__}: {e}")
            results[tag] = "error"

    # 错开 2s 开第二条,便于看"后连的是否踢掉先连的"
    await asyncio.gather(one("A"), _delayed(2, one, "B"))
    print(f"  结论: A={results.get('A')} B={results.get('B')}")
    if results.get("A") == "alive" and results.get("B") == "alive":
        print("  → 服务端允许单账号多连(不互踢)")
    else:
        print("  → 单账号疑似单连/互踢,看上面谁被关")


async def _delayed(sec, coro, *args):
    await asyncio.sleep(sec)
    return await coro(*args)


async def cmd_badtoken():
    bad = TOKEN[:-6] + "AAAAAA"   # 篡改签名尾部
    print("[badtoken] 用篡改签名的 token 连接,观察拒绝行为")
    t0 = time.time()
    try:
        async with websockets.connect(url(token=bad), ssl=SSL_CTX, ping_interval=None,
                                      open_timeout=15) as ws:
            init = await _drain_initial(ws, 3)
            print(f"  握手成功(未在握手阶段拒绝),初始帧={init}")
            try:
                while time.time() - t0 < 15:
                    await asyncio.wait_for(ws.recv(), timeout=15 - (time.time() - t0))
            except asyncio.TimeoutError:
                print("  15s 仍未被关 → 服务端未校验 token 有效性(或延迟校验)")
            except websockets.ConnectionClosed as e:
                print(f"  ✗ 连后被关 code={e.code} reason={e.reason!r} (存活 {time.time()-t0:.1f}s)")
    except websockets.InvalidStatus as e:
        print(f"  握手被拒(HTTP status): {e}")
    except Exception as e:
        print(f"  异常: {type(e).__name__}: {e}")


async def cmd_hold(sec=600):
    print(f"[hold] 带 30s ping 保活挂 {sec}s,看是否被强制关(最大会话时长)")
    t0 = time.time()
    try:
        async with websockets.connect(url(), ssl=SSL_CTX, ping_interval=None, open_timeout=15) as ws:
            await _drain_initial(ws)
            ka = asyncio.create_task(_keepalive(ws, "hold"))
            try:
                while time.time() - t0 < sec:
                    await asyncio.wait_for(ws.recv(), timeout=sec - (time.time() - t0))
            except asyncio.TimeoutError:
                print(f"  ✓ {sec}s 内一直存活,未观察到最大会话限制")
            except websockets.ConnectionClosed as e:
                print(f"  ✗ {ts()} 被关 code={e.code} reason={e.reason!r} (存活 {time.time()-t0:.1f}s)")
            finally:
                ka.cancel()
    except Exception as e:
        print(f"  异常: {type(e).__name__}: {e}")


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "idle"
    arg = sys.argv[2] if len(sys.argv) > 2 else None
    if cmd == "idle":
        asyncio.run(cmd_idle(int(arg) if arg else 3))
    elif cmd == "ping":
        asyncio.run(cmd_ping(arg or "qgb-ping-123"))
    elif cmd == "concurrent":
        asyncio.run(cmd_concurrent())
    elif cmd == "badtoken":
        asyncio.run(cmd_badtoken())
    elif cmd == "hold":
        asyncio.run(cmd_hold(int(arg) if arg else 600))
    else:
        print(__doc__)


if __name__ == "__main__":
    main()
