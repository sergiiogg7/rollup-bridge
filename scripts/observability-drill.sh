#!/usr/bin/env bash
# A controlled failure that proves the alerts fire: the challenger process dies while a lying output is pending.
#
#   scripts/observability-up.sh            (Prometheus + Grafana, same STATUS_PORT / CHALLENGER_STATUS_PORT / PROM_PORT)
#   scripts/observability-drill.sh         ~4 minutes; exits 0 only if every expectation held
#
# Topology: Anvil L1 + L2, `rollup-bridge all` WITHOUT the challenger (DISABLE_SERVICES), and the challenger as its own
# process whose PID this script kills. Needs java, anvil, cast, forge, curl; test keys only.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
WORK="$ROOT/.demo-drill"; rm -rf "$WORK"; mkdir -p "$WORK"
L1_PORT=${L1_PORT:-18545}; L2_PORT=${L2_PORT:-19545}; STATUS_PORT=${STATUS_PORT:-18088}
CHALLENGER_STATUS_PORT=${CHALLENGER_STATUS_PORT:-18089}; PROM_PORT=${PROM_PORT:-9090}
L1=http://127.0.0.1:$L1_PORT; L2=http://127.0.0.1:$L2_PORT; PROM=http://127.0.0.1:$PROM_PORT
RELAYER_KEY=0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d
PROPOSER_KEY=0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a
CHALLENGER_KEY=0x7c852118294e51e653712a81e05800f419141751be58f605c371e15141b007a6
GUARDIAN_KEY=0x47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926a
ALICE_KEY=0x8b3a350cf5c34c9194ca85829a2df0ec3153be0318b5e2d3348e872092edffba
MALLORY_KEY=0x4bbbf85ce3377467afe5d46f804f221813b2bb87f24d81f60f1fcdbf7cbf4356
ALICE=0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc; BOB=0x976EA74026E726554dB657fA54763abd0C3a0aa9
JAR="$ROOT/services/app/target/rollup-bridge.jar"; JAVA="java --enable-native-access=ALL-UNNAMED"
PIDS=(); cleanup() { for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done; }; trap cleanup EXIT
say() { printf '\n==> %s\n' "$*"; }
fail() { echo "EXPECTATION FAILED: $*" >&2; tail -20 "$WORK/services.log" >&2 || true; exit 1; }
wait_for() { local d="$1" t="$2"; shift 2; for _ in $(seq "$t"); do "$@" >/dev/null 2>&1 && return 0; sleep 1; done; fail "timeout waiting for $d"; }
q() { curl -sf --get "$PROM/api/v1/query" --data-urlencode "query=$1"; }          # instant query, raw JSON
has_series() { q "$1" | grep -q '"result":\[{'; }
firing() { has_series "ALERTS{alertname=\"$1\",alertstate=\"firing\"}"; }
[ -f "$JAR" ] || { echo "build first: ./mvnw -DskipTests package" >&2; exit 1; }
curl -sf "$PROM/-/ready" >/dev/null || { echo "Prometheus is not running on $PROM_PORT: scripts/observability-up.sh" >&2; exit 1; }

say "1. Chains, contracts, services (the challenger as a separate process)"
anvil --silent --port "$L1_PORT" --chain-id 31337 --block-time 1 >"$WORK/anvil-l1.log" 2>&1 & PIDS+=($!)
anvil --silent --port "$L2_PORT" --chain-id 901 >"$WORK/anvil-l2.log" 2>&1 & PIDS+=($!)
wait_for L1 20 cast chain-id --rpc-url "$L1"; wait_for L2 20 cast chain-id --rpc-url "$L2"
scripts/install-deps.sh >/dev/null; (cd contracts && forge build >/dev/null 2>&1)
rm -f deployments/l1.json deployments/l2.json
(cd contracts && forge script script/LocalDeploy.s.sol --tc LocalDeploy --sig "runL1()" --rpc-url "$L1" --broadcast >/dev/null 2>&1 \
  && forge script script/LocalDeploy.s.sol --tc LocalDeploy --sig "runL2()" --rpc-url "$L2" --broadcast >/dev/null 2>&1)
jget() { sed -n "s/.*\"$2\":\"\\(0x[0-9a-fA-F]*\\)\".*/\\1/p" "$1"; }
L1BRIDGE=$(jget deployments/l1.json l1Bridge); L2BRIDGE=$(jget deployments/l2.json l2Bridge); TOKEN=$(jget deployments/l2.json bridgedEth)
export L1_RPC=$L1 L2_RPC=$L2 OUTPUT_ORACLE=$(jget deployments/l1.json outputOracle) L1_BRIDGE=$L1BRIDGE L2_BRIDGE=$L2BRIDGE \
       RELAYER_KEY PROPOSER_KEY CHALLENGER_KEY GUARDIAN_KEY MALLORY_KEY DEPOSIT_CONFIRMATIONS=2 POLL_MS=500 CHALLENGER_POLL_MS=2000 DB_DIR="$WORK/db"
STATUS_PORT=$STATUS_PORT DISABLE_SERVICES=challenger $JAVA -jar "$JAR" all >"$WORK/services.log" 2>&1 & PIDS+=($!)
STATUS_PORT=$CHALLENGER_STATUS_PORT $JAVA -jar "$JAR" challenger >"$WORK/challenger.log" 2>&1 & CHALLENGER_PID=$!; PIDS+=($CHALLENGER_PID)
wait_for "services /metrics" 40 curl -sf "http://127.0.0.1:$STATUS_PORT/metrics"
wait_for "challenger /metrics" 40 curl -sf "http://127.0.0.1:$CHALLENGER_STATUS_PORT/metrics"
cast rpc anvil_setBalance 0x14dC79964da2C08b23698B3D3cc7Ca32193d9955 0xde0b6b3a7640000 --rpc-url "$L1" >/dev/null

say "2. Normal traffic: a deposit and a withdrawal, so that every panel has data"
cast send "$L1BRIDGE" "depositETH(address)" "$ALICE" --value 5ether --rpc-url "$L1" --private-key "$ALICE_KEY" >/dev/null
minted() { [ "$(cast call "$TOKEN" 'balanceOf(address)(uint256)' "$ALICE" --rpc-url "$L2" | awk '{print $1}')" != 0 ]; }
wait_for "deposit minted" 40 minted
cast send "$L2BRIDGE" "initiateWithdrawal(address,uint256)" "$BOB" 1ether --rpc-url "$L2" --private-key "$ALICE_KEY" >/dev/null
wait_for "output proposed" 40 has_series 'bridge_outputs{status="proposed"} > 0'
wait_for "both targets UP" 30 has_series 'up{job="bridge"} == 1 and on() up{job="bridge-challenger"} == 1'
echo "targets up; the two alerts under test are quiet (a leftover from an earlier run, e.g. ServiceErrorsHigh, may still show):"
q 'ALERTS{alertstate="firing"}' | head -c 300; echo
! firing ChallengerDown && ! firing OutputWindowClosingUnchecked || fail "an alert under test is firing before the fault"

say "3. FAULT: kill the challenger (pid $CHALLENGER_PID), then the proposer key posts a lying output"
kill "$CHALLENGER_PID"
cast rpc anvil_mine 1 --rpc-url "$L2" >/dev/null
$JAVA -jar "$JAR" demo-attack fake-leaf 4000000000000000000
wait_for "ChallengerDown to fire" 90 firing ChallengerDown
echo "ChallengerDown is FIRING"
wait_for "OutputWindowClosingUnchecked to fire (more than half of the 120 s window gone, unchecked)" 120 firing OutputWindowClosingUnchecked
echo "OutputWindowClosingUnchecked is FIRING"
q 'ALERTS{alertstate="firing"}'; echo
q 'bridge_unchecked_output_min_window_remaining_seconds'; echo
say "3b. While the alerts fire: every target, rule and dashboard query checked through the HTTP APIs"
GRAFANA_PORT=${GRAFANA_PORT:-3000} PROM_PORT=$PROM_PORT scripts/observability-check.sh | tee "$WORK/evidence.txt" || fail "observability-check.sh"

say "4. RECOVERY: restart the challenger; it challenges, the guardian rules, the alerts resolve"
STATUS_PORT=$CHALLENGER_STATUS_PORT $JAVA -jar "$JAR" challenger >"$WORK/challenger2.log" 2>&1 & PIDS+=($!)
deleted() { has_series 'bridge_outputs{status="deleted"} > 0'; }
wait_for "the lying output to be DELETED" 90 deleted
quiet() { ! has_series 'ALERTS{alertstate="firing",alertname=~"ChallengerDown|OutputWindowClosingUnchecked"}'; }
wait_for "both alerts to resolve" 60 quiet
echo "resolved. Counters:"; q 'bridge_outputs_challenged_total'; echo
say "drill passed: the alerts fired during the failure and cleared after the recovery"
