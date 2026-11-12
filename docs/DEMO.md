# Demo output

Real output of `scripts/demo.sh`, captured on 2026-11-12 (Windows 10, Git Bash, JDK 25.0.5, Foundry 1.7.1, two native Anvil processes). Nothing was edited except this header; block numbers, hashes and addresses differ between runs, the assertions do not.

What happens, in order:

1. Two Anvil chains start (L1 chain id 31337 with 1 s blocks, L2 chain id 901) and the contracts are deployed with `forge script` (challenge window 120 s).
2. The five Java services start in one JVM (`rollup-bridge all`).
3. **Deposit**: Alice locks 5 ETH on L1. The relayer waits for 2 confirmations, re-checks the block hash, and mints 5 bETH on L2.
4. **Withdrawal**: Alice burns 2 bETH on L2 to pay Bob. The proposer posts the output, the withdrawal relayer proves it, and the clock is moved forward with `evm_increaseTime` instead of waiting 120 s. The relayer finalizes and Bob receives exactly 2 ETH.
5. **Attack**: the proposer key turns malicious and posts an output with a fabricated withdrawal paying Mallory 4 ETH; Mallory's forged proof is accepted while the output is merely `Proposed`. The challenger rebuilds the tree from L2 events, challenges, the guardian rules, and the output (output 1) is `DELETED`. Mallory then waits far beyond the window and cannot cash out. The honest proposer re-posts the correct state as output 2.

The demo does not include an L1 reorg; those scenarios (E6, E7) are automated tests in `services/e2e`.

```text

==> 0. Preflight
openjdk version "25.0.5" 2026-10-20 LTS

==> 1. Starting two chains: L1 (31337, 1 s blocks) and L2 (901)
    L1 chain id 31337, L2 chain id 901

==> 2. Deploying the Solidity contracts with Foundry (lab challenge window: 120 s)
  OutputOracle: 0x5FbDB2315678afecb367f032d93F642f64180aa3
  L1Bridge:     0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512
  L2Bridge:    0x5FbDB2315678afecb367f032d93F642f64180aa3
  BridgedETH:  0xa16E02E87b7454126E5E10d957A927A7F5B5d2be

==> 3. Starting the Java services: deposit relayer, proposer, withdrawal relayer, challenger, guardian
    status page: http://127.0.0.1:18088/   logs: .demo/services.log

==> 4. DEPOSIT. Alice locks 5 ETH on L1; the relayer waits for 2 confirmations, re-checks the block hash, then mints 5 bETH on L2
blockNumber          3
status               1 (success)
    L1 bridge now holds 5.000000000000000000 ETH
    Alice L2 balance: 5.000000000000000000 bETH

==> 5. WITHDRAWAL. Alice burns 2 bETH on L2 to pay Bob on L1; this appends a leaf to the L2 withdrawal Merkle tree
blockNumber          3
status               1 (success)
    withdrawal root on L2: 0xc70fd620386179fb66b1b3136d3af4cd46dcf460c95a1611011707fcaa9ac793
rollup-bridge status
  L1 head: 9   L2 head: 3   L1 paused: false

Outputs on L1 (1)
  #    status      l2 block  withdrawals finalizable  root
  0    PROPOSED    3         1           false        0xc70fd6..c793

Deposits (L1 -> L2) as seen by the deposit relayer (1)
  #0   5000000000000000000 wei -> 0x996550..a4dc  L1 block 3  Done[l2TxHash=0x56f01ef87a078a57cbd6ca08650fc5abfbb201177ccb467d478ac3ad0dfb7a60]

Withdrawals (L2 -> L1) as seen by the withdrawal relayer (1)
  #0   2000000000000000000 wei -> 0x976ea7..0aa9  Proven[outputIndex=0, proveTx=0x533073f77f8c3a0a2f960324b72388bc55b6615d63c497ef607d72b831b5b0e2]

    the withdrawal is proven but NOT payable yet: the challenge window (120 s) is open

==> 6. Fast-forward the L1 clock past the window with evm_increaseTime (no waiting)
    Bob's L1 balance grew by 2.000000000000000000 ETH
    L1 bridge still holds 3.000000000000000000 ETH == 3 ETH backing Alice's remaining 3 bETH

==> 7. ATTACK. The proposer key turns malicious: it posts an output with a FABRICATED withdrawal paying Mallory 4 ETH, and Mallory proves it
malicious output #1 posted in tx 0xb5a53add90cb83da2bde9c7d62b81c8ba1ff3fe6c49a055e28a64e5e32d5d2a8
the fabricated withdrawal #1 (4000000000000000000 wei to 0x14dc79964da2c08b23698b3d3cc7ca32193d9955) was PROVEN against it
    the forged proof is accepted while the output is merely Proposed: only the 120 s challenge window stands between Mallory and 4 ETH
    (the challenger polls every 3 s in this demo, so the proof lands first; it still has the whole window to react)
    the challenger rebuilt the tree from L2 events, found the extra leaf and posted its bond
rollup-bridge status
  L1 head: 18   L2 head: 4   L1 paused: false

Outputs on L1 (3)
  #    status      l2 block  withdrawals finalizable  root
  0    PROPOSED    3         1           true         0xc70fd6..c793
  1    DELETED     4         2           false        0xe7ea33..3b57
  2    PROPOSED    4         1           false        0xc70fd6..c793

Deposits (L1 -> L2) as seen by the deposit relayer (1)
  #0   5000000000000000000 wei -> 0x996550..a4dc  L1 block 3  Done[l2TxHash=0x56f01ef87a078a57cbd6ca08650fc5abfbb201177ccb467d478ac3ad0dfb7a60]

Withdrawals (L2 -> L1) as seen by the withdrawal relayer (1)
  #0   2000000000000000000 wei -> 0x976ea7..0aa9  Finalized[txHash=0x4b1ff5427b50ed1e344eb4b0c35f718c1e329bbf7ff627fea32cae4105e28f6a, paid=true]


==> 8. Mallory waits far beyond the window and tries to cash out
Error: Failed to estimate gas: server returned an error response: error code 3: execution reverted: custom error 0x1ce9932e, data: "0x1ce9932e": OutputNotFinalizable
    rejected: the output was deleted, the theft never pays. Mallory balance did not increase.
    challenger credits on the oracle (own bond back + the slashed proposer bond): 1.500000000000000000 ETH

==> 9. Final state
rollup-bridge status
  L1 head: 22   L2 head: 4   L1 paused: false

Outputs on L1 (3)
  #    status      l2 block  withdrawals finalizable  root
  0    PROPOSED    3         1           true         0xc70fd6..c793
  1    DELETED     4         2           false        0xe7ea33..3b57
  2    PROPOSED    4         1           true         0xc70fd6..c793

Deposits (L1 -> L2) as seen by the deposit relayer (1)
  #0   5000000000000000000 wei -> 0x996550..a4dc  L1 block 3  Done[l2TxHash=0x56f01ef87a078a57cbd6ca08650fc5abfbb201177ccb467d478ac3ad0dfb7a60]

Withdrawals (L2 -> L1) as seen by the withdrawal relayer (1)
  #0   2000000000000000000 wei -> 0x976ea7..0aa9  Finalized[txHash=0x4b1ff5427b50ed1e344eb4b0c35f718c1e329bbf7ff627fea32cae4105e28f6a, paid=true]

    assertions passed: deposit minted once, withdrawal paid once after the window, attack caught and never paid, L1 funds intact
rollup-bridge status
  L1 head: 22   L2 head: 4   L1 paused: false


==> Done. Stopping Anvils and services.
```
