#!/bin/bash
# 单个预设的弱网压测,跑 60 秒后输出关键指标。
# 用法:./run_weaknet_test.sh <preset-name> <output-file>

set -e
DEVICE=79URX18C10009758
PRESET=$1
OUT=$2
DURATION=60

if [ -z "$PRESET" ] || [ -z "$OUT" ]; then
    echo "用法: $0 <preset> <output-file>"
    exit 1
fi

echo "=== 预设: $PRESET ==="

# 清场
adb -s $DEVICE shell am force-stop org.daimhim.imc_core.weaknet
adb -s $DEVICE shell am force-stop org.daimhim.imc_core.demo
adb -s $DEVICE logcat -c

# 启动 weaknet + 立即起 VPN
adb -s $DEVICE shell am start -n org.daimhim.imc_core.weaknet/.WeakNetMainActivity \
    --es targetPackage "org.daimhim.imc_core.demo" \
    --es preset "$PRESET" \
    --ez autoStart true > /dev/null
echo "  weaknet 启动,等 4s VPN 就绪..."
sleep 4

# 启动 :app + 自动连
TOKEN="eyJ0eXAiOiJKV1QiLCJhbGciOiJSUzUxMiJ9.eyJzdWIiOiIxMTk5MjQ0MzIxMjU0MTUwMTQ0Iiwic2NvcGUiOiJkZWZhdWx0IiwiaXNzIjoiMTUwMTUxMTIwMDgiLCJsb2dpbiI6MTc3ODY1NTc1MX0.BQdjONVuZhSgMZrimHyplcqO8NYXlGYO-KUA-rjn9EvPRdAQGlIn95L2ukarAC5TOUnxYFImaF7u_YtEtoWaUGqTBNohUmJiCdY5B9xRlWoE23EcXKB6PSXIXIcWvZzG9oFBv9-jz1SbnxMtPK0H4jiHuK4U4B9N71BAD-SAjmA"
adb -s $DEVICE shell am start -n org.daimhim.imc_core.demo/.QgbWsTestActivity \
    --es token "$TOKEN" \
    --es name "202012221018295" \
    --es state "0" \
    --ez autoConnect true > /dev/null
echo "  :app 启动并触发自动连,运行 ${DURATION}s..."
sleep $DURATION

# 抓 logcat 写到文件
adb -s $DEVICE logcat -d > "$OUT" 2>&1

# 计指标
echo "  完成,采集指标..."
ENGINE_ON=$(grep -c "engineOn key=" "$OUT" || echo 0)
ON_OPEN=$(grep -c "onOpen 101 Switching" "$OUT" || echo 0)
ON_CLOSE_REMOTE=$(grep -c "onError" "$OUT" || echo 0)
MSG_BYTES=$(grep -c "onMessage bytes:" "$OUT" || echo 0)
TRANSITIONS_CONNECTED=$(grep -c "transition → Connected" "$OUT" || echo 0)
TRANSITIONS_RECONNECTING=$(grep -c "transition → Reconnecting" "$OUT" || echo 0)
CHAOS_DISCONNECT=$(grep -c "chaos.*disconnect" "$OUT" || echo 0)
TCP_FLOWS_CREATED=$(grep -c "\[tcp\] 新建" "$OUT" || echo 0)

# 找首次 engineOn 与首次 onOpen 时间戳,算握手时长
T_ENGINE=$(grep "engineOn key=" "$OUT" | head -1 | awk '{print $2}')
T_OPEN=$(grep "onOpen 101 Switching" "$OUT" | head -1 | awk '{print $2}')

cat <<EOF

  ┌─ 结果 ─────────────────────────────
  │ engineOn 调用次数      : $ENGINE_ON
  │ onOpen 成功握手次数    : $ON_OPEN
  │ onError 异常事件次数   : $ON_CLOSE_REMOTE
  │ onMessage 收消息次数   : $MSG_BYTES
  │ → Connected 状态切换  : $TRANSITIONS_CONNECTED
  │ → Reconnecting 状态切换: $TRANSITIONS_RECONNECTING
  │ TCP flow 新建次数      : $TCP_FLOWS_CREATED
  │ chaos 触发断开次数     : $CHAOS_DISCONNECT
  │ 首次 engineOn @ $T_ENGINE
  │ 首次 onOpen   @ $T_OPEN
  └────────────────────────────────────

EOF

adb -s $DEVICE shell am force-stop org.daimhim.imc_core.weaknet
adb -s $DEVICE shell am force-stop org.daimhim.imc_core.demo
