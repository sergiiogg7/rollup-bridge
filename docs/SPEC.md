# Specification

What the system does, precisely. Source of truth for behaviour is the code and its tests; this file explains the design.

## 1. Overview

ETH is locked in `L1Bridge`. A relayer mirrors each deposit by minting `bETH` (an ERC-20) on L2. To leave, a holder burns `bETH` on L2; the burn appends a **leaf** to an append-only Merkle tree kept by `L2Bridge`. A **proposer** periodically posts the tree root (an *output*) to `OutputOracle` on L1. Anyone can prove a withdrawal against a posted root with a Merkle proof, but L1 pays only after a **challenge window**, during which a **challenger** can dispute the output by posting a bond. A **guardian** resolves disputes (MVP trust assumption).

Analogy for a Java backend developer: the output root is a signed checksum of the L2 "withdrawal table"; the challenge window is a four-eyes review period; the bonds are a deposit that makes frivolous disputes and fraudulent submissions expensive.

## 2. Data

### Withdrawal and leaf

```
Withdrawal = (uint256 id, address sender, address target, uint256 amount)
leaf       = keccak256(abi.encode(id, sender, target, amount))      // 128-byte preimage
node(a, b) = keccak256(min(a, b) ++ max(a, b))                      // 64-byte preimage, COMMUTATIVE
```

- `id` is the leaf index (`withdrawalCount` before the insert).
- Leaf and node preimages have different lengths, so a leaf can never be passed off as an inner node (domain separation). `L1Bridge` additionally requires the proof to have exactly 20 siblings.
- The commutative (sorted) pair hash means a proof is a plain list of siblings with no left/right flags and verifies with OpenZeppelin `MerkleProof.verify`. Consequence: the position of a leaf is not proven, but the `id` is inside the leaf and `id < output.withdrawalCount` is checked.

### Tree

`IncrementalMerkleTree`: depth 20, `2**20 - 1` usable leaves (like the Ethereum deposit contract, the last slot is never used so the frontier stays valid). Zero hashes: `zeros[0] = 0`, `zeros[i+1] = node(zeros[i], zeros[i])`; the empty tree root is `zeros[20]`. Only the left frontier (20 words) is stored; insert and root are `O(depth)`. A leaf equal to `bytes32(0)` would be indistinguishable from an empty slot, which cannot happen for a keccak output.

### Output

```
Output { withdrawalRoot, l2BlockHash, l2BlockNumber, withdrawalCount, proposedAt,
         status: Proposed | Challenged | Deleted, proposer, challenger, challengedAt, bondSettled }
```

An output claims: "at L2 block `l2BlockNumber` (hash `l2BlockHash`) the tree had `withdrawalCount` leaves and root `withdrawalRoot`". Each root commits to **all** earlier withdrawals, so a bad output taints every later one.

## 3. Contracts

| Contract | Chain | Role |
|---|---|---|
| `BridgedETH` | L2 | ERC-20 `bETH`; `mint`/`burn` only by `L2Bridge` |
| `L2Bridge` | L2 | `finalizeDeposit` (relayer only, replay guard per deposit id), `initiateWithdrawal` (burn + append leaf, emits `newRoot`), `withdrawalRoot()`, `withdrawalCount()` |
| `OutputOracle` | L1 | outputs, bonds, challenges, guardian verdicts, pull-payment credits |
| `L1Bridge` | L1 | `depositETH`, `proveWithdrawal`, `finalizeWithdrawal`, `claim`, `pause`/`unpause`, caps |

### OutputOracle rules

- `proposeOutput(root, l2BlockHash, l2BlockNumber, count)`: proposer only, `msg.value == PROPOSER_BOND`. Compared with the newest **live** (non-deleted) output, `l2BlockNumber` must strictly increase and `count` must not shrink.
- `challengeOutput(i)`: anyone, `msg.value == CHALLENGER_BOND`, output `Proposed`, `now < proposedAt + CHALLENGE_WINDOW`.
- `resolveChallenge(i, proposerWasWrong)`: guardian only, within `RESOLUTION_PERIOD` of the challenge.
  - proposer wrong: output `i` **and every later output** are deleted; challenger gets both bonds; later proposers get their bonds back (they are collateral damage, not slashed).
  - challenger wrong: output returns to `Proposed` with a fresh window; the proposer gets the challenger bond.
- `expireChallenge(i)`: anyone, after `RESOLUTION_PERIOD` without a verdict. **Fail-safe**: the output and all later ones are deleted and every bond is refunded. A silent guardian therefore cannot freeze the bridge or burn bonds, but it can force a re-proposal.
- `deleteFrom(i)`: guardian emergency deletion of `i` and later outputs while `i` is not yet final. Without a challenger nothing is confiscated (bonds refunded); with a challenge pending it counts as an upheld challenge.
- `claimProposerBond(i)`: after the window, if nothing is under challenge.
- `isFinalizable(i)`: `Proposed`, window elapsed, **and `openChallenges == 0`**. While any challenge is open no output finalizes, so a bad later output cannot slip through while an earlier one is being judged.
- Money moves by pull payments (`credits`, `withdrawCredits`) so a hostile recipient cannot block resolution.

### L1Bridge rules

- `depositETH(to)`: not paused, `to != 0`, `0 < value <= maxDepositAmount`, `totalLocked + value <= maxTotalLocked`. Emits `DepositInitiated(depositId, from, to, amount)`; ids are a counter.
- `proveWithdrawal(w, outputIndex, proof)`: not paused; proof length 20; output `Proposed`; `w.id < output.withdrawalCount`; not finalized; proof valid. Re-proving is allowed only if the previously used output is `Deleted`.
- `finalizeWithdrawal(w)`: not paused, non-reentrant; proven; the proving output `isFinalizable`; `w.amount <= totalLocked`. Effects first (`finalized`, `totalLocked`), then a low-level call that ignores return data (no return-bomb). If the call fails the amount goes to `claimable[target]` and `claim()` pays it later (also while paused).
- `totalLocked` = deposits minus finalized withdrawals. `balance == totalLocked + totalClaimable` always (barring forced ETH), and a finalization above `totalLocked` reverts: L1 never pays more than it was given.
- `pause`/`unpause`: guardian only (read from the oracle). Blocks deposit, prove and finalize; never blocks `claim`.

## 4. Off-chain services

All are polling `tick()` loops over **ports**; progress is kept in a `BridgeStore` (SQLite in production). They reconcile with the chain before acting, so they are idempotent.

| Service | Reads | Writes | Notes |
|---|---|---|---|
| Deposit relayer | L1 `DepositInitiated` logs, L1 canonical hashes, L2 `processedDeposits` | L2 `finalizeDeposit` | confirmations rule + canonical-hash re-check; persisted `Relaying` marker before sending |
| Proposer | L2 head, root and count at that block, newest live output | L1 `proposeOutput` | stateless: re-proposes after a deletion or a reorg |
| Withdrawal relayer | L2 `WithdrawalInitiated` logs, outputs, proofs | L1 `proveWithdrawal`, `finalizeWithdrawal` | builds its own tree; never proves against an output whose root differs from its own |
| Challenger | outputs, L2 logs and block hashes | L1 `challengeOutput` | rebuilds the tree from raw event fields, recomputing every leaf |
| Guardian | challenged outputs | L1 `resolveChallenge`, `pause` | re-derives the output with its own verifier before ruling |

### State machines (sealed types)

```
Deposit:     Seen -> Confirmed -> Relaying -> Done(l2Tx)
             Seen/Confirmed -> Dropped (block reorged away);  Dropped -> Seen (id re-appears)
             Relaying -> Confirmed (send failed, retry)
Withdrawal:  Initiated -> Included(out) -> Proven(out) -> Finalized(paid | credited)
             Included/Proven -> Invalidated(out) (output deleted or vanished) -> Included(new out)
```

`Finalizable` is not stored; it is a function of the chain.

## 5. Parameters

| Parameter | Where | Local lab value | Meaning |
|---|---|---|---|
| `CHALLENGE_WINDOW` | oracle | 120 s (tests 100 s) | time to dispute an output |
| `RESOLUTION_PERIOD` | oracle | 600 s (tests 500 s) | time the guardian has to rule |
| `PROPOSER_BOND` / `CHALLENGER_BOND` | oracle | 1 / 0.5 ETH | skin in the game |
| `maxDepositAmount` / `maxTotalLocked` | L1 bridge | 10 / 100 ETH | early-stage safety caps |
| `DEPOSIT_CONFIRMATIONS` | relayer | 2 | depth before minting on L2 |

Real deployments would use a window of days (7 in the Optimism family) and bonds sized to the value at risk.

## 6. Invariants (tested)

- L1 solvency: `balance == totalLocked + totalClaimable == deposited - paid - claimed`.
- `totalLocked == deposited - sum(finalized amounts)`.
- A withdrawal leaf finalizes at most once.
- Nothing finalizes before its window ends, nor while any challenge is open.
- L2 supply of `bETH` equals relayed deposits minus burned withdrawals.
- `withdrawalCount` only grows.
- Oracle balance equals unsettled proposer bonds plus open challenger bonds plus unclaimed credits; `openChallenges` equals the number of `Challenged` outputs.

## 7. Known sharp edges

- **Deposit ids are an L1 counter.** After an L1 reorg a *different* deposit can get an id that was already minted on L2 (only reachable if the relayer minted at too low a depth). The L2 replay guard then blocks the legitimate deposit and the relayer cannot tell the two apart from L2 state. A production design keys by `(id, l1 tx hash)` or by a content hash. See THREAT-MODEL.
- **Gas griefing by the proposer role**: `deleteFrom`/cascade loops over outputs proposed since the last final one. The proposer is a trusted role; a production design bounds the number of unfinalized outputs.
- **Guardian can censor**: a challenge against an honest output makes all exits wait (global `openChallenges` gate) until it is resolved or expires. The cost to the challenger is the bond if the guardian rules against it.
