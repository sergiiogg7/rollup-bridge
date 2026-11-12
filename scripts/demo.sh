#!/usr/bin/env bash
# End-to-end demo of rollup-bridge on two local Anvil chains (L1 chain id 31337, L2 chain id 901).
#
#   scripts/demo.sh            run the whole story, then stop everything
#   KEEP=1 scripts/demo.sh     leave Anvils + services running (status page on http://127.0.0.1:18088/)
#
# Needs on the PATH: a JDK 25 (java), foundry (anvil, cast, forge). No Docker. Test keys only, never reuse them.
# The story: deposit -> withdrawal (window fast-forwarded with evm_increaseTime) -> an attack the challenger catches.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
WORK="$ROOT/.demo"; rm -rf "$WORK"; mkdir -p "$WORK"
L1_PORT=${L1_PORT:-18545}; L2_PORT=${L2_PORT:-19545}; STATUS_PORT=${STATUS_PORT:-18088}   # not 8545/9545, to coexist with other local devnets
L1=http://127.0.0.1:$L1_PORT; L2=http://127.0.0.1:$L2_PORT

# Anvil public development accounts (TEST KEYS ONLY)
DEPLOYER_KEY=0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80
RELAYER_KEY=0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d
PROPOSER_KEY=0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a
CHALLENGER_KEY=0x7c852118294e51e653712a81e05800f419141751be58f605c371e15141b007a6
GUARDIAN_KEY=0x47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926a
ALICE_KEY=0x8b3a350cf5c34c9194ca85829a2df0ec3153be0318b5e2d3348e872092edffba
MALLORY_KEY=0x4bbbf85ce3377467afe5d46f804f221813b2bb87f24d81f60f1fcdbf7cbf4356
ALICE=0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc
BOB=0x976EA74026E726554dB657fA54763abd0C3a0aa9
MALLORY=0x14dC79964da2C08b23698B3D3cc7Ca32193d9955

if [ -n "${NO_COLOR:-}" ] || [ ! -t 1 ] && [ -z "${FORCE_COLOR:-}" ]; then B=""; R=""; else B=$'\033[1m'; R=$'\033[0m'; fi
say()   { printf '\n%s==> %s%s\n' "$B" "$*" "$R"; }
note()  { printf '    %s\n' "$*"; }
eth()   { cast from-wei "$1"; }
PIDS=()
cleanup() {
  [ "${KEEP:-0}" = 1 ] && return
  for p in "${PIDS[@]:-}"; do kill "$p" 2>/dev/null || true; done
}
trap cleanup EXIT
wait_for() { # wait_for "<description>" <seconds> <command...>
  local d="$1" t="$2"; shift 2
  for _ in $(seq "$t"); do if "$@" >/dev/null 2>&1; then return 0; fi; sleep 1; done
  echo "TIMEOUT waiting for: $d" >&2; tail -30 "$WORK/services.log" >&2 || true; exit 1
}
fail() { echo "ASSERTION FAILED: $*" >&2; exit 1; }
num()  { awk '{print $1}'; }                       # first token of a cast output ("123 [1.2e2]" -> "123")
bal()  { cast balance "$1" --rpc-url "$2"; }
tokbal() { cast call "$TOKEN" "balanceOf(address)(uint256)" "$1" --rpc-url "$L2" | num; }
JAVA="java --enable-native-access=ALL-UNNAMED"
status() { $JAVA -jar "$JAR" status; }
jget() { sed -n "s/.*\"$2\":\"\\(0x[0-9a-fA-F]*\\)\".*/\\1/p" "$1"; }

say "0. Preflight"
for tool in java anvil cast forge; do command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }; done
java -version 2>&1 | head -1
JAR="$ROOT/services/app/target/rollup-bridge.jar"
if [ ! -f "$JAR" ]; then
  say "Building the Java services (Maven wrapper, first run downloads dependencies)"
  ./mvnw -q -DskipTests package
fi
scripts/install-deps.sh >/dev/null
(cd contracts && forge build >/dev/null 2>&1)

say "1. Starting two chains: L1 (31337, 1 s blocks) and L2 (901)"
anvil --silent --port "$L1_PORT" --chain-id 31337 --block-time 1 >"$WORK/anvil-l1.log" 2>&1 & PIDS+=($!)
anvil --silent --port "$L2_PORT" --chain-id 901 >"$WORK/anvil-l2.log" 2>&1 & PIDS+=($!)
wait_for "L1" 20 cast chain-id --rpc-url "$L1"
wait_for "L2" 20 cast chain-id --rpc-url "$L2"
note "L1 chain id $(cast chain-id --rpc-url "$L1"), L2 chain id $(cast chain-id --rpc-url "$L2")"

say "2. Deploying the Solidity contracts with Foundry (lab challenge window: 120 s)"
rm -f deployments/l1.json deployments/l2.json
(cd contracts \
  && forge script script/LocalDeploy.s.sol --tc LocalDeploy --sig "runL1()" --rpc-url "$L1" --broadcast 2>&1 | grep -E "OutputOracle:|L1Bridge:" \
  && forge script script/LocalDeploy.s.sol --tc LocalDeploy --sig "runL2()" --rpc-url "$L2" --broadcast 2>&1 | grep -E "L2Bridge:|BridgedETH:")
ORACLE=$(jget deployments/l1.json outputOracle); L1BRIDGE=$(jget deployments/l1.json l1Bridge)
L2BRIDGE=$(jget deployments/l2.json l2Bridge);   TOKEN=$(jget deployments/l2.json bridgedEth)

cast rpc anvil_setBalance "$BOB" 0xde0b6b3a7640000 --rpc-url "$L1" >/dev/null
cast rpc anvil_setBalance "$MALLORY" 0xde0b6b3a7640000 --rpc-url "$L1" >/dev/null

say "3. Starting the Java services: deposit relayer, proposer, withdrawal relayer, challenger, guardian"
export L1_RPC=$L1 L2_RPC=$L2 OUTPUT_ORACLE=$ORACLE L1_BRIDGE=$L1BRIDGE L2_BRIDGE=$L2BRIDGE \
       RELAYER_KEY PROPOSER_KEY CHALLENGER_KEY GUARDIAN_KEY MALLORY_KEY \
       DEPOSIT_CONFIRMATIONS=2 POLL_MS=500 CHALLENGER_POLL_MS=3000 DB_DIR="$WORK/db" STATUS_PORT=$STATUS_PORT
$JAVA -jar "$JAR" all >"$WORK/services.log" 2>&1 & PIDS+=($!)
wait_for "status page" 30 curl -sf "http://127.0.0.1:$STATUS_PORT/"
note "status page: http://127.0.0.1:$STATUS_PORT/   logs: .demo/services.log"

say "4. DEPOSIT. Alice locks 5 ETH on L1; the relayer waits for 2 confirmations, re-checks the block hash, then mints 5 bETH on L2"
cast send "$L1BRIDGE" "depositETH(address)" "$ALICE" --value 5ether --rpc-url "$L1" --private-key "$ALICE_KEY" | grep -E "^(status|blockNumber)"
note "L1 bridge now holds $(eth "$(bal "$L1BRIDGE" "$L1")") ETH"
has_bETH() { [ "$(tokbal "$ALICE")" != "0" ]; }
wait_for "deposit on L2" 40 has_bETH
note "Alice L2 balance: $(eth "$(tokbal "$ALICE")") bETH"
[ "$(tokbal "$ALICE")" = "5000000000000000000" ] || fail "Alice should hold exactly 5 bETH"

say "5. WITHDRAWAL. Alice burns 2 bETH on L2 to pay Bob on L1; this appends a leaf to the L2 withdrawal Merkle tree"
BOB_BEFORE=$(bal "$BOB" "$L1")   # 1 ETH
cast send "$L2BRIDGE" "initiateWithdrawal(address,uint256)" "$BOB" 2ether --rpc-url "$L2" --private-key "$ALICE_KEY" | grep -E "^(status|blockNumber)"
note "withdrawal root on L2: $(cast call "$L2BRIDGE" 'withdrawalRoot()(bytes32)' --rpc-url "$L2")"
proven() { status | grep -q "#0 .*Proven"; }
wait_for "output proposed and withdrawal proven" 40 proven
status
note "the withdrawal is proven but NOT payable yet: the challenge window (120 s) is open"
[ "$(bal "$BOB" "$L1")" = "$BOB_BEFORE" ] || fail "Bob must not be paid before the window ends"

say "6. Fast-forward the L1 clock past the window with evm_increaseTime (no waiting)"
cast rpc evm_increaseTime 125 --rpc-url "$L1" >/dev/null
bob_paid() { [ "$(bal "$BOB" "$L1")" != "$BOB_BEFORE" ]; }
wait_for "Bob paid" 40 bob_paid
note "Bob's L1 balance grew by $(eth "$(( $(bal "$BOB" "$L1") - BOB_BEFORE ))") ETH"
[ "$(( $(bal "$BOB" "$L1") - BOB_BEFORE ))" = "2000000000000000000" ] || fail "Bob should have received exactly 2 ETH"
note "L1 bridge still holds $(eth "$(bal "$L1BRIDGE" "$L1")") ETH == 3 ETH backing Alice's remaining 3 bETH"
[ "$(bal "$L1BRIDGE" "$L1")" = "3000000000000000000" ] || fail "bridge solvency"

say "7. ATTACK. The proposer key turns malicious: it posts an output with a FABRICATED withdrawal paying Mallory 4 ETH, and Mallory proves it"
# (balances of Bob and Mallory are reset to 1 ETH so that the shell can do exact 64-bit arithmetic on them)
cast rpc anvil_mine 1 --rpc-url "$L2" >/dev/null   # L2 moves on by a block, so a new output must claim a newer block
$JAVA -jar "$JAR" demo-attack fake-leaf 4000000000000000000
note "the forged proof is accepted while the output is merely Proposed: only the 120 s challenge window stands between Mallory and 4 ETH"
note "(the challenger polls every 3 s in this demo, so the proof lands first; it still has the whole window to react)"
caught() { status | grep -qE "^ +1 +(CHALLENGED|DELETED)"; }
wait_for "challenger to react" 30 caught
note "the challenger rebuilt the tree from L2 events, found the extra leaf and posted its bond"
deleted() { status | grep -qE "^ +1 +DELETED"; }
wait_for "guardian to rule" 30 deleted
status

say "8. Mallory waits far beyond the window and tries to cash out"
cast rpc evm_increaseTime 1000 --rpc-url "$L1" >/dev/null
MAL_BEFORE=$(bal "$MALLORY" "$L1")
sleep 2
set +e
OUT=$(cast send "$L1BRIDGE" "finalizeWithdrawal((uint256,address,address,uint256))" "(1,$MALLORY,$MALLORY,4000000000000000000)" \
      --rpc-url "$L1" --private-key "$MALLORY_KEY" 2>&1)
RC=$?
set -e
echo "$OUT" | tail -3
[ "$RC" != 0 ] || fail "the forged withdrawal must be rejected"
[ "$(bal "$MALLORY" "$L1")" = "$MAL_BEFORE" ] || fail "Mallory must not have gained anything"
note "rejected: the output was deleted, the theft never pays. Mallory balance did not increase."
note "challenger credits on the oracle (own bond back + the slashed proposer bond): $(eth "$(cast call "$ORACLE" 'credits(address)(uint256)' 0x90F79bf6EB2c4f870365E785982E1f101E93b906 --rpc-url "$L1" | num)") ETH"
[ "$(bal "$L1BRIDGE" "$L1")" = "3000000000000000000" ] || fail "depositors funds must be intact"

say "9. Final state"
status
note "assertions passed: deposit minted once, withdrawal paid once after the window, attack caught and never paid, L1 funds intact"
curl -s "http://127.0.0.1:$STATUS_PORT/" | head -3

if [ "${KEEP:-0}" = 1 ]; then
  say "KEEP=1: everything keeps running. Status page: http://127.0.0.1:$STATUS_PORT/   Stop with: kill ${PIDS[*]}  (or pkill anvil; pkill -f rollup-bridge.jar)"
else
  say "Done. Stopping Anvils and services."
fi
