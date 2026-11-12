# Stretch A: an interactive dispute game over a toy VM

This is a **toy, standalone fault-proof mechanism**. It shows how an optimistic system can settle "who computed the right state?" on-chain without re-running the whole computation. It is **not connected to the bridge**: `OutputOracle` still lets the trusted guardian decide challenges (see [THREAT-MODEL.md](THREAT-MODEL.md)). Nothing here has been audited.

Contents: [what it proves](#what-it-proves) · [the machine](#the-toy-vm) · [the protocol](#the-protocol) · [a worked example](#worked-example-real-output) · [why the honest side wins](#why-the-honest-side-wins) · [cost](#cost-olog-n) · [tests](#how-it-is-tested) · [what it does NOT do](#what-it-does-not-do) · [how a real system extends it](#from-this-toy-to-a-real-system)

## What it proves

A *proposer* bonds money and claims: "running the committed program for `N` steps from the state with hash `H0` ends in the state with hash `HN`". A *challenger* bonds money and disputes it. After about `2 * log2(N)` short moves the contract has narrowed the disagreement to **one instruction**, executes that instruction itself, and pays the winner. The contract never replays the `N` steps; it only ever runs one.

What is established, precisely: for the committed program, a claim that is false is refuted by an honest challenger who answers every move in time, and a claim that is true cannot be refuted. That is all. See [what it does NOT do](#what-it-does-not-do).

## The toy VM

`contracts/src/disputes/ToyVM.sol` (Java twin: `services/dispute/.../vm/ToyVm.java`).

- State: a program counter and four `uint64` registers. State hash: `keccak256(abi.encode(pc, r0, r1, r2, r3))`, five 32-byte words.
- Program: at most 64 instructions of exactly 11 bytes, `op | a | b | imm(8, big endian)`, committed on-chain as `keccak256(program)`.

| op | name | effect |
|---|---|---|
| 0 | `HALT` | nothing (the state is a fixed point) |
| 1 | `MOV` | `r[a] = imm`, `pc += 1` |
| 2 | `ADD` | `r[a] += r[b]` (mod 2^64), `pc += 1` |
| 3 | `SUB` | `r[a] -= r[b]` (mod 2^64), `pc += 1` |
| 4 | `MUL` | `r[a] *= r[b]` (mod 2^64), `pc += 1` |
| 5 | `JNZ` | `pc = imm` if `r[a] != 0`, else `pc += 1` |

`step` is **total**: it never reverts. A `pc` past the end of the program, an unknown opcode and a register index above 3 all behave like `HALT`. This is a deliberate design rule: the contested step of a dispute must always be executable, otherwise one bad instruction in a program could freeze a game forever.

## The protocol

`contracts/src/disputes/BisectionGame.sol`. One contract holds many independent games (own bonds, own clock). It is constructed with the program hash, the per-move timeout and the two bond sizes.

```
createGame(initialHash, N, claimedHash)  payable   proposer: claim + bond, clock starts
challenge(id)                            payable   anybody: bond, before the deadline
loop while the interval (lo, hi) has more than one step:
    bisect(id, mid, midHash)                       proposer, mid = lo + (hi-lo)/2 (the contract computes it)
    respond(id, agree)                             challenger: agree -> lo = mid ; disagree -> hi = mid
step(id, program, preState)                        anybody: runs ONE instruction on-chain
claimTimeout(id)                                   anybody, once a deadline has passed
withdraw()                                         pull payment of the credited bonds
```

Invariant of the loop: both sides have agreed on the state hash at `lo`; they disagree about the hash at `hi` (initially `0` and `N`). Every `respond` halves the interval. At `hi == lo + 1`, `step` takes a pre-state that must hash to the agreed `loHash` and a program that must hash to the committed program hash, executes one instruction and compares the result with the proposer's claim at `hi`: equal means the proposer was right, different means the proposer lied.

Rules of the clock and the money:

- Every move must be sent before the current deadline (`MoveTimeExpired` otherwise); every move restarts the clock.
- After a deadline, anybody can call `claimTimeout`: if it was the proposer's turn to bisect, the proposer loses; if it was the challenger's turn (no challenge yet, no response, no one-step proof) the proposer wins. An unchallenged claim simply stands and the proposer gets its bond back.
- The loser's bond goes to the winner. Money moves by **pull payments** (`credits`, `withdraw`, protected by a reentrancy guard): a receiver that rejects ETH only blocks itself.
- Moves out of turn, by the wrong account, with the wrong step number, in the wrong phase, with a wrong pre-state or the wrong program all revert with a named custom error.

Why the proposer names the midpoint step and the contract does not trust a caller-supplied index: the contract computes `mid` itself, and `bisect` still takes the step number as an argument so that a stale or malformed move fails loudly (`WrongStep`).

## Worked example (real output)

Program `sum 3+2+1 into r1` (7 instructions), 12 steps. The proposer follows the true execution up to step 6, **corrupts `r1` at step 7** (one register off by one) and then plays a perfectly valid execution from the corrupted state, so its final claim (`r1 = 7`) is false (`r1` is really 6). Output of `WorkedExampleTest` (Java model of the contract; hashes shortened):

```
true state after 0 steps: pc=0 r=[0, 0, 0, 0]
true state after 3 steps: pc=3 r=[3, 0, 1, 0]
true state after 6 steps: pc=3 r=[2, 3, 1, 0]
true state after 7 steps: pc=4 r=[2, 5, 1, 0]
true state after 9 steps: pc=3 r=[1, 5, 1, 0]
true state after 12 steps: pc=6 r=[0, 6, 1, 0]
1 proposer  createGame: after 12 steps the state hash is 0x71678415.. (FALSE)  bond 1.0 ETH
2 challenger challenge: disputes the claim, bond 0.5 ETH, interval (lo=0, hi=12)
  proposer  bisect (lo=0, hi=12): claims step 6 = 0xd52ed8d0.. (true)
  challenger respond: agrees, lower bound moves up -> interval (lo=6, hi=12)
  proposer  bisect (lo=6, hi=12): claims step 9 = 0x7deae897.. (FALSE)
  challenger respond: disagrees, upper bound moves down -> interval (lo=6, hi=9)
  proposer  bisect (lo=6, hi=9): claims step 7 = 0xeb13d0c1.. (FALSE)
  challenger respond: disagrees, upper bound moves down -> interval (lo=6, hi=7)
  challenger step: executes ONE instruction of the program on pc=3 r=[2, 3, 1, 0] (step 6),
      gets pc=4 r=[2, 5, 1, 0] -> hash 0xd35ac124.., the proposer claimed 0xeb13d0c1..
result: proposerWon=false via ONE_STEP_PROOF in 9 moves
contested transition: step 6 -> 7
```

Reading it: the challenger agreed with the (true) state at step 6 and disagreed with the claims at 9 and at 7, so the dispute shrank from `(0,12)` to `(6,7)`. The contract executed the single instruction at `pc=3` (`ADD r1, r0`) on the agreed state of step 6 and got `r1 = 5`; the proposer had claimed something else. The challenger receives both bonds (1.5 ETH). Nine on-chain calls decided a computation of twelve steps; the same protocol decides a computation of 2^60 steps in 123.

## Why the honest side wins

Call a hash *true* if it is the real state hash at that step.

- **Honest challenger.** It only moves `lo` up to a hash it verified as true and only moves `hi` down to a hash it knows is false (the claimed final hash is false, otherwise it would not have challenged). So the invariant "`lo` true, `hi` false" survives every round. When the interval is one step, the true state at `hi` is `step(true state at lo)`, which differs from the false claim: the one-step proof refutes the proposer.
- **Honest proposer.** Its midpoints are all true and its final claim is true. Whichever half the challenger picks, one end is true and the other end is true: at the last step the claim equals the executed instruction's result, so the proof vindicates the proposer.
- **Timeouts** cannot hurt a party that moves in time. The one-step proof is permissionless, so liveness never depends on one account.

These two arguments are exactly what the randomized tests check (see below).

## Cost: O(log N)

A disputed game costs `create + challenge + 2 per halving + one step` calls. For `N = 2^k` steps that is **exactly `2k + 3`** calls, for any lie (asserted for `k = 0..62` in `LogarithmicCostTest`); for any other `N` it is between `2*floor(log2 N) + 3` and `2*ceil(log2 N) + 3`. One billion steps need at most 63 calls; the on-chain execution is a single instruction. On the live contract (Anvil) a game over `2^20` steps took exactly 43 transactions for every lying strategy tried (`LiveGameTest`).

## How it is tested

Solidity (`contracts/test/disputes/`): 83 unit/fuzz tests plus 3 invariants, 100 % line, statement, branch and function coverage of `src/disputes/`.

- `ToyVM.t.sol`: one test per opcode, wrap-around arithmetic, every trap rule, fuzz that `step` never reverts and touches at most one register.
- `BisectionGame.t.sol`: the mechanics, **one named test per attack** (move out of turn, wrong step, wrong bounds, third party moves, timeout claimed early, timeout claimed twice, move after resolution, challenge twice, one-step proof with a wrong pre-state, with another step's state, with another program, in the wrong phase, after the deadline, reentrant `withdraw`, receiver that rejects ETH, ...), parallel games, and fuzz: an honest challenger always beats a lying proposer wherever the lie starts, an honest proposer always beats a random challenger, any wrong pre-state is refused.
- `BisectionGame.invariants.t.sol`: random sequences of every action over several games by three actors who sometimes cheat: the contract's balance always equals running bonds plus unwithdrawn credits, intervals are always well formed, a verdict never changes.

Java (`services/dispute`, 29 tests): a reference VM, an in-memory model of the contract, bots and an EVM client.

- **Differential tests, VM.** `testdata/dispute-vectors.json` is generated by a Foundry script (`GenerateDisputeVectors.s.sol`) with the real `ToyVM.step`: 424 pseudo-random programs and states (all opcodes, invalid opcodes and registers, out-of-range counters) plus two whole traces. Java must give the same post-state and hashes. On top, `DifferentialVmLiveTest` draws 3000+ fresh random cases per run plus an exhaustive opcode x register grid and `eth_call`s the real Solidity library on Anvil (through the `VmHarness` test contract).
- **Differential tests, game.** `ModelVsContractTest` applies identical random move sequences (legal and illegal, by right and wrong accounts, on time and late, one shared clock) to the deployed contract and to the Java model and requires the same outcome, the same custom-error name, the same full game state and the same credits after every move.
- **Randomized games** on the model (`RandomGamesTest`): 3000 games with N up to 2^40 against lying proposers (lie only at the end, lie from the start, consistent alternative execution from a corrupted step, garbage, random mixtures) and 1500 games of an honest proposer against five kinds of lying challenger (disagree always, agree always, coin flip, forged pre-state, offline). The honest side won every one. Also: offline proposer loses on the clock, unchallenged claim stands, 60 parallel interleaved games.
- **Live games** (`LiveGameTest`): the same bots play on the deployed contract; forged proofs are refused by the contract itself.

Not done: nothing here is fuzzed with a coverage-guided tool beyond Foundry's own fuzzer, the Java bots are test tools (no daemon), and the contract has not been run through any external review.

## What it does NOT do

1. **It does not verify the L2 state transition.** It proves statements about the ToyVM program only. The bridge's outputs are Merkle roots of withdrawals produced by a chain that is not a ToyVM program.
2. **It is not wired to the bridge.** `OutputOracle.resolveChallenge` is still decided by the guardian. Nothing in `L1Bridge` or `OutputOracle` calls `BisectionGame`.
3. **It is a two-party game, and that has a known hole** (executable as `test_Limitation_aSybilChallengerLetsALieStand` and `sybilChallengerLetsALieStand`): a lying proposer can open a game and challenge it from a second address it controls, then let that "challenger" run out of time. The false claim stands, and an honest observer cannot take the challenger's seat. Address checks cannot fix this (sybils). Production systems close it with many-party games: every claim in the bisection tree can be countered by *anybody*, bonds grow with depth so attacking is expensive, and a claim is settled only when its whole subtree is.
4. **No economic design.** Bonds are fixed constants; nothing prices gas, griefing or the cost of being forced to respond; the clock is one constant per move (no "chess clock" with a bounded total time).
5. **Liveness is the challenger's job**: a challenger that is offline for one move deadline loses. That is the same assumption as the bridge's challenge window.
6. The VM is trivially small. Realistic systems must fix the state commitment (a Merkle tree of memory, not five registers), I/O and pre-image oracles, and a program that is itself pinned by a hash of a real binary.

## From this toy to a real system

What would replace the guardian: the program being bisected is the **L2 state-transition function** compiled to a small deterministic instruction set (a MIPS or RISC-V VM in the Cannon/OP-style designs, WASM in Arbitrum-style ones), the state hash commits to registers *and a memory Merkle root*, the one-step contract executes one instruction against Merkle-proven memory, and L1 data (block headers, withdrawal data) is read through a hash pre-image oracle. `OutputOracle.challengeOutput` would open a game for the contested output instead of waiting for the guardian, and the game result (`proposerWon`) would settle the output and the bonds. That is a different project in size: the one-step VM, the memory model and the many-party game are the hard parts, and none of them exist in this repository.

## Reproduce

```bash
cd contracts && forge test --match-path 'test/disputes/*'          # 86 Solidity tests
cd contracts && forge script script/GenerateDisputeVectors.s.sol --tc GenerateDisputeVectors   # rewrites ../testdata/dispute-vectors.json
./mvnw -T 1 -pl services/dispute -am test                           # Java; the live tests need `anvil` on the PATH
./mvnw -T 1 -pl services/dispute test -Dtest=WorkedExampleTest      # prints the worked example above
```
