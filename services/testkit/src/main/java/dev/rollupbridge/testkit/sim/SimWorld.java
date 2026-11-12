package dev.rollupbridge.testkit.sim;

import dev.rollupbridge.core.Abi;
import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Hex;
import dev.rollupbridge.core.Keccak256;
import dev.rollupbridge.core.Withdrawal;
import dev.rollupbridge.core.WithdrawalTree;
import dev.rollupbridge.core.model.DepositEvent;
import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.model.OutputStatus;
import dev.rollupbridge.core.model.ProofInfo;
import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.core.model.WithdrawalEvent;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L1Writer;
import dev.rollupbridge.core.port.L2Reader;
import dev.rollupbridge.core.port.L2Writer;
import dev.rollupbridge.testkit.Accounts;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * An in-memory model of both chains and of the four Solidity contracts, with the same entry points as the real
 * gateways (the ports of {@code core}), so the real services can run against it thousands of times per second.
 *
 * <p>What it is: the rules of {@code L1Bridge}, {@code OutputOracle} and {@code L2Bridge} restated in Java (access
 * control, window arithmetic, cascade deletion, proof checking with the real {@link WithdrawalTree}), an automining
 * L1 with block hashes and timestamps, and L1 reorgs that drop or re-include transactions (even in another order).
 * What it is not: the contracts. Bonds, pull payments and gas are left out; the Foundry tests and the Anvil end-to-end
 * scenarios remain the source of truth for the contract rules, and a few scenarios run on both to keep the model honest.
 *
 * <p>Not thread safe on purpose: scripts are single threaded and deterministic for a given seed.
 */
public final class SimWorld {
    public static final BigInteger ETH = BigInteger.TEN.pow(18);

    public record Params(long challengeWindow, long resolutionPeriod, BigInteger maxDeposit, BigInteger maxTotalLocked) {
        public static Params defaults() {
            return new Params(100, 500, ETH.multiply(BigInteger.valueOf(100)), ETH.multiply(BigInteger.valueOf(100_000)));
        }
    }

    /** How a reorg treats the transactions of the blocks it removes. */
    public enum Reorg {
        /** They are gone (never re-broadcast). */
        DROP,
        /** They are mined again, in the same order. */
        REINCLUDE,
        /** They are mined again in reverse order: a deposit can now get another id. */
        REINCLUDE_REVERSED
    }

    private static final String FINALIZED_TOPIC = Hex.encode(Keccak256.hash("WithdrawalFinalized(bytes32,address,uint256,bool)"));

    // ---------------------------------------------------------------------------------------------
    // L1 model
    // ---------------------------------------------------------------------------------------------

    /** A reverted call. */
    private static final class Revert extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Revert(String why) {
            super(why);
        }
    }

    private interface L1Tx {
        /** Applies the transaction to {@code s} at block time {@code ts}; throws {@link Revert} without side effects. */
        List<TxResult.LogEntry> apply(L1State s, long ts);
    }

    private record TxRecord(L1Tx tx, DepositEvent[] deposit) {}

    private static final class L1Block {
        final long number;
        final Bytes32 hash;
        final long timestamp;
        final List<TxRecord> txs = new ArrayList<>();

        L1Block(long number, Bytes32 hash, long timestamp) {
            this.number = number;
            this.hash = hash;
            this.timestamp = timestamp;
        }
    }

    private static final class Out {
        Bytes32 root;
        Bytes32 l2Hash;
        long l2Number;
        long count;
        long proposedAt;
        OutputStatus status = OutputStatus.PROPOSED;
        String proposer;
        long challengedAt;
        String challenger;
    }

    private final class L1State {
        long depositCount;
        BigInteger totalLocked = BigInteger.ZERO;
        boolean paused;
        long openChallenges;
        final List<Out> outputs = new ArrayList<>();
        final Map<Long, DepositEvent> deposits = new TreeMap<>();
        final Map<Bytes32, long[]> proofs = new HashMap<>();
        final Set<Bytes32> finalized = new HashSet<>();
        final Map<String, BigInteger> paid = new TreeMap<>();

        long latestLive() {
            for (int i = outputs.size() - 1; i >= 0; i--) {
                if (outputs.get(i).status != OutputStatus.DELETED) {
                    return i;
                }
            }
            return -1;
        }

        Out out(long index) {
            if (index < 0 || index >= outputs.size()) {
                throw new Revert("OutputNotFound(" + index + ")");
            }
            return outputs.get((int) index);
        }

        boolean finalizable(long index, long ts) {
            if (index >= outputs.size()) {
                return false;
            }
            Out o = outputs.get((int) index);
            return o.status == OutputStatus.PROPOSED && openChallenges == 0 && ts >= o.proposedAt + params.challengeWindow();
        }

        void cascade(long from) {
            for (int i = (int) from; i < outputs.size(); i++) {
                Out o = outputs.get(i);
                if (o.status == OutputStatus.DELETED) {
                    continue;
                }
                if (o.status == OutputStatus.CHALLENGED) {
                    openChallenges--;
                }
                o.status = OutputStatus.DELETED;
            }
        }

        void deleteUpheld(long index, Out o) {
            openChallenges--;
            o.status = OutputStatus.DELETED;
            cascade(index + 1);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // L2 model
    // ---------------------------------------------------------------------------------------------

    private record L2Block(long number, Bytes32 hash, Bytes32 rootAfter, long countAfter) {}

    private final Params params;
    private final List<L1Block> l1Chain = new ArrayList<>();
    private L1State l1 = new L1State();
    private long hashSalt;
    private long pendingWarp;

    private final List<L2Block> l2Chain = new ArrayList<>();
    private final WithdrawalTree l2Tree = new WithdrawalTree();
    private final List<WithdrawalEvent> l2Events = new ArrayList<>();
    private final Map<Long, BigInteger[]> processed = new TreeMap<>(); // depositId -> {amount}; recipient in processedTo
    private final Map<Long, String> processedTo = new TreeMap<>();
    private final Map<String, BigInteger> l2Balance = new TreeMap<>();
    private BigInteger l2Supply = BigInteger.ZERO;
    private BigInteger l2Burned = BigInteger.ZERO;

    public SimWorld() {
        this(Params.defaults());
    }

    public SimWorld(Params params) {
        this.params = params;
        l1Chain.add(new L1Block(0, l1Hash(0), 1_000_000));
        l2Chain.add(new L2Block(0, l2Hash(0), new WithdrawalTree().root(), 0));
    }

    public Params params() {
        return params;
    }

    private Bytes32 l1Hash(long number) {
        return Bytes32.of(Keccak256.hash("l1-" + number + "-" + (hashSalt++)));
    }

    private static Bytes32 l2Hash(long number) {
        return Bytes32.of(Keccak256.hash("l2-" + number));
    }

    // ---------------------------------------------------------------------------------------------
    // L1 mining and reorgs
    // ---------------------------------------------------------------------------------------------

    private L1Block head() {
        return l1Chain.get(l1Chain.size() - 1);
    }

    private L1Block mineBlock() {
        long number = head().number + 1;
        L1Block b = new L1Block(number, l1Hash(number), head().timestamp + 1 + pendingWarp);
        pendingWarp = 0;
        l1Chain.add(b);
        return b;
    }

    /** Mines {@code tx} in its own block (automine) or throws if it reverts; returns the receipt. */
    private TxResult sendL1(L1Tx tx) {
        long ts = head().timestamp + 1 + pendingWarp;
        List<TxResult.LogEntry> logs;
        try {
            logs = tx.apply(l1, ts);
        } catch (Revert r) {
            throw new GatewayException("reverted: " + r.getMessage(), "0x", null);
        }
        L1Block b = mineBlock();
        DepositEvent[] ev = new DepositEvent[1];
        if (tx instanceof DepositTx) {
            DepositEvent last = l1.deposits.get(l1.depositCount - 1);
            ev[0] = new DepositEvent(last.depositId(), last.from(), last.to(), last.amount(), b.number, b.hash);
            l1.deposits.put(last.depositId(), ev[0]);
        }
        b.txs.add(new TxRecord(tx, ev));
        return new TxResult(txHash(b), true, b.number, logs, null);
    }

    private static String txHash(L1Block b) {
        return Hex.encode(Keccak256.hash("tx-" + b.hash.hex()));
    }

    /** Mines {@code n} empty L1 blocks. */
    public void mineL1(int n) {
        for (int i = 0; i < n; i++) {
            mineBlock();
        }
    }

    /** Moves the L1 clock forward (like {@code evm_increaseTime}) and mines a block so that it is visible. */
    public void warpL1(long seconds) {
        pendingWarp += seconds;
        mineBlock();
    }

    /**
     * Replaces the top {@code depth} blocks by new ones with new hashes. Transactions of the removed blocks are
     * dropped or mined again, then the whole L1 state is recomputed by replaying the canonical chain.
     */
    public void reorgL1(int depth, Reorg mode) {
        if (depth <= 0 || depth >= l1Chain.size()) {
            throw new IllegalArgumentException("bad reorg depth " + depth);
        }
        List<L1Block> removed = new ArrayList<>(l1Chain.subList(l1Chain.size() - depth, l1Chain.size()));
        for (int i = 0; i < depth; i++) {
            l1Chain.remove(l1Chain.size() - 1);
        }
        List<TxRecord> again = new ArrayList<>();
        removed.forEach(b -> again.addAll(b.txs));
        if (mode == Reorg.REINCLUDE_REVERSED) {
            Collections.reverse(again);
        }
        long ts = removed.get(0).timestamp;
        List<TxRecord> queue = mode == Reorg.DROP ? List.of() : again;
        for (int i = 0; i < depth; i++) {
            long number = head().number + 1;
            L1Block b = new L1Block(number, l1Hash(number), ts + i);
            if (i < queue.size()) {
                b.txs.add(queue.get(i));
            }
            l1Chain.add(b);
        }
        // Reinclusion can need more blocks than removed (never: one tx per block); anything beyond is simply gone.
        replay();
    }

    /** Recomputes the L1 state from genesis; transactions that now revert are dropped from their block. */
    private void replay() {
        l1 = new L1State();
        for (L1Block b : l1Chain) {
            List<TxRecord> kept = new ArrayList<>();
            for (TxRecord r : b.txs) {
                try {
                    r.tx.apply(l1, b.timestamp);
                } catch (Revert dropped) {
                    continue;
                }
                if (r.tx instanceof DepositTx) {
                    DepositEvent last = l1.deposits.get(l1.depositCount - 1);
                    DepositEvent ev = new DepositEvent(last.depositId(), last.from(), last.to(), last.amount(), b.number, b.hash);
                    l1.deposits.put(ev.depositId(), ev);
                    kept.add(new TxRecord(r.tx, new DepositEvent[] {ev}));
                } else {
                    kept.add(r);
                }
            }
            b.txs.clear();
            b.txs.addAll(kept);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // L1 transactions (the contract rules)
    // ---------------------------------------------------------------------------------------------

    private record DepositTx(String from, String to, BigInteger amount, Params params) implements L1Tx {
        @Override
        public List<TxResult.LogEntry> apply(L1State s, long ts) {
            if (s.paused) {
                throw new Revert("IsPaused()");
            }
            if (amount.signum() == 0) {
                throw new Revert("ZeroAmount()");
            }
            if (amount.compareTo(params.maxDeposit()) > 0) {
                throw new Revert("DepositTooLarge()");
            }
            if (s.totalLocked.add(amount).compareTo(params.maxTotalLocked()) > 0) {
                throw new Revert("TotalLockedExceeded()");
            }
            s.totalLocked = s.totalLocked.add(amount);
            long id = s.depositCount++;
            s.deposits.put(id, new DepositEvent(id, from, to, amount, -1, Bytes32.ZERO));
            return List.of();
        }
    }

    private static void requireSender(String sender, String expected, String error) {
        if (!sender.equals(expected)) {
            throw new Revert(error);
        }
    }

    /** User action on L1: {@code depositETH}. Returns the receipt; the deposit id is in {@link #l1Deposits()}. */
    public TxResult deposit(Accounts from, Accounts to, BigInteger amount) {
        return sendL1(new DepositTx(from.address(), to.address(), amount, params));
    }

    /** The deposits of the canonical L1 chain, by id. */
    public Map<Long, DepositEvent> l1Deposits() {
        return Collections.unmodifiableMap(l1.deposits);
    }

    // ---------------------------------------------------------------------------------------------
    // L2 actions
    // ---------------------------------------------------------------------------------------------

    private L2Block l2Head() {
        return l2Chain.get(l2Chain.size() - 1);
    }

    private void mineL2(Bytes32 rootAfter) {
        long n = l2Head().number + 1;
        l2Chain.add(new L2Block(n, l2Hash(n), rootAfter, l2Tree.size()));
    }

    /** Mines {@code n} empty L2 blocks. */
    public void mineL2(int n) {
        for (int i = 0; i < n; i++) {
            mineL2(l2Tree.root());
        }
    }

    /** User action on L2: burns {@code amount} of {@code from} and appends a withdrawal leaf. Returns its id. */
    public long withdraw(Accounts from, Accounts target, BigInteger amount) {
        BigInteger have = l2Balance.getOrDefault(from.address(), BigInteger.ZERO);
        if (amount.signum() == 0 || have.compareTo(amount) < 0) {
            throw new GatewayException("reverted: ERC20InsufficientBalance", "0x", null);
        }
        l2Balance.put(from.address(), have.subtract(amount));
        l2Supply = l2Supply.subtract(amount);
        l2Burned = l2Burned.add(amount);
        Withdrawal w = new Withdrawal(l2Tree.size(), from.address(), target.address(), amount);
        l2Tree.append(w.leaf());
        mineL2(l2Tree.root());
        L2Block b = l2Head();
        l2Events.add(new WithdrawalEvent(w, w.leaf(), b.rootAfter(), b.number(), b.hash()));
        return w.id();
    }

    // ---------------------------------------------------------------------------------------------
    // Observers for the invariants
    // ---------------------------------------------------------------------------------------------

    public BigInteger l1TotalLocked() {
        return l1.totalLocked;
    }

    /** Total ETH paid out on L1 to {@code who} by finalized withdrawals. */
    public BigInteger l1Paid(String who) {
        return l1.paid.getOrDefault(who, BigInteger.ZERO);
    }

    public BigInteger l1PaidTotal() {
        return l1.paid.values().stream().reduce(BigInteger.ZERO, BigInteger::add);
    }

    public BigInteger l2TotalSupply() {
        return l2Supply;
    }

    public BigInteger l2Balance(Accounts who) {
        return l2Balance.getOrDefault(who.address(), BigInteger.ZERO);
    }

    public BigInteger l2TotalBurned() {
        return l2Burned;
    }

    /** Deposit id to {@code {to, amount}} of everything minted on L2. */
    public Map<Long, DepositEvent> l2Minted() {
        Map<Long, DepositEvent> out = new TreeMap<>();
        processed.forEach((id, v) -> out.put(id, new DepositEvent(id, "", processedTo.get(id), v[0], -1, Bytes32.ZERO)));
        return out;
    }

    public List<WithdrawalEvent> l2WithdrawalEvents() {
        return Collections.unmodifiableList(l2Events);
    }

    public boolean isFinalizedOnL1(Withdrawal w) {
        return l1.finalized.contains(w.leaf());
    }

    public int l1OutputCount() {
        return l1.outputs.size();
    }

    /** Statuses of every output ever proposed (for assertions). */
    public List<OutputStatus> l1OutputStatuses() {
        return l1.outputs.stream().map(o -> o.status).toList();
    }

    public long l1Head() {
        return head().number;
    }

    public long l1Time() {
        return head().timestamp;
    }

    // ---------------------------------------------------------------------------------------------
    // Gateways: what the services see
    // ---------------------------------------------------------------------------------------------

    /** Reader and writer of L1 for one account (the account is {@code msg.sender} of every transaction). */
    public Gateway1 l1(Accounts role) {
        return new Gateway1(role == null ? null : role.address());
    }

    /** Reader and writer of L2 for one account. */
    public Gateway2 l2(Accounts role) {
        return new Gateway2(role == null ? null : role.address());
    }

    public final class Gateway1 implements L1Reader, L1Writer {
        private final String sender;

        private Gateway1(String sender) {
            this.sender = sender;
        }

        @Override
        public long headNumber() {
            return head().number;
        }

        @Override
        public Optional<Bytes32> canonicalHash(long number) {
            return number >= 0 && number < l1Chain.size() ? Optional.of(l1Chain.get((int) number).hash) : Optional.empty();
        }

        @Override
        public long headTimestamp() {
            return head().timestamp;
        }

        @Override
        public List<DepositEvent> depositEvents(long fromBlock, long toBlock) {
            List<DepositEvent> out = new ArrayList<>();
            for (L1Block b : l1Chain) {
                if (b.number < fromBlock || b.number > toBlock) {
                    continue;
                }
                for (TxRecord r : b.txs) {
                    if (r.deposit()[0] != null) {
                        out.add(r.deposit()[0]);
                    }
                }
            }
            return out;
        }

        @Override
        public long outputCount() {
            return l1.outputs.size();
        }

        @Override
        public OutputRecord output(long index) {
            Out o = l1.out(index);
            return new OutputRecord(
                    index, o.root, o.l2Hash, o.l2Number, o.count, o.proposedAt, o.status,
                    o.proposer, o.challenger == null ? "0x0000000000000000000000000000000000000000" : o.challenger,
                    o.challengedAt, false);
        }

        @Override
        public Optional<OutputRecord> latestLiveOutput() {
            long i = l1.latestLive();
            return i < 0 ? Optional.empty() : Optional.of(output(i));
        }

        @Override
        public boolean isFinalizable(long outputIndex) {
            return l1.finalizable(outputIndex, head().timestamp);
        }

        @Override
        public ProofInfo proof(Bytes32 leaf) {
            long[] p = l1.proofs.get(leaf);
            return p == null ? ProofInfo.NONE : new ProofInfo(true, p[0], p[1]);
        }

        @Override
        public boolean isFinalized(Bytes32 leaf) {
            return l1.finalized.contains(leaf);
        }

        @Override
        public boolean isPaused() {
            return l1.paused;
        }

        @Override
        public long challengeWindow() {
            return params.challengeWindow();
        }

        @Override
        public BigInteger proposerBond() {
            return ETH;
        }

        @Override
        public BigInteger challengerBond() {
            return ETH.divide(BigInteger.TWO);
        }

        @Override
        public BigInteger claimable(String account) {
            return BigInteger.ZERO;
        }

        // ----- writer -----

        private TxResult tx(L1Tx tx) {
            if (sender == null) {
                throw new GatewayException("read-only gateway: no signing key configured");
            }
            return sendL1(tx);
        }

        @Override
        public TxResult proposeOutput(Bytes32 root, Bytes32 l2BlockHash, long l2BlockNumber, long withdrawalCount) {
            String who = sender;
            return tx((s, ts) -> {
                requireSender(who, Accounts.PROPOSER.address(), "NotProposer()");
                long last = s.latestLive();
                if (last >= 0) {
                    Out prev = s.outputs.get((int) last);
                    if (l2BlockNumber <= prev.l2Number || withdrawalCount < prev.count) {
                        throw new Revert("NotMonotonic()");
                    }
                }
                Out o = new Out();
                o.root = root;
                o.l2Hash = l2BlockHash;
                o.l2Number = l2BlockNumber;
                o.count = withdrawalCount;
                o.proposedAt = ts;
                o.proposer = who;
                s.outputs.add(o);
                return List.of();
            });
        }

        @Override
        public TxResult challengeOutput(long index) {
            String who = sender;
            return tx((s, ts) -> {
                Out o = s.out(index);
                if (o.status != OutputStatus.PROPOSED) {
                    throw new Revert("WrongStatus()");
                }
                if (ts >= o.proposedAt + params.challengeWindow()) {
                    throw new Revert("WindowClosed()");
                }
                o.status = OutputStatus.CHALLENGED;
                o.challenger = who;
                o.challengedAt = ts;
                s.openChallenges++;
                return List.of();
            });
        }

        @Override
        public TxResult resolveChallenge(long index, boolean proposerWasWrong) {
            String who = sender;
            return tx((s, ts) -> {
                requireSender(who, Accounts.GUARDIAN.address(), "NotGuardian()");
                Out o = s.out(index);
                if (o.status != OutputStatus.CHALLENGED) {
                    throw new Revert("WrongStatus()");
                }
                if (ts > o.challengedAt + params.resolutionPeriod()) {
                    throw new Revert("ResolutionExpired()");
                }
                if (proposerWasWrong) {
                    s.deleteUpheld(index, o);
                } else {
                    s.openChallenges--;
                    o.status = OutputStatus.PROPOSED;
                    o.challenger = null;
                    o.challengedAt = 0;
                    o.proposedAt = ts;
                }
                return List.of();
            });
        }

        @Override
        public TxResult expireChallenge(long index) {
            return tx((s, ts) -> {
                Out o = s.out(index);
                if (o.status != OutputStatus.CHALLENGED) {
                    throw new Revert("WrongStatus()");
                }
                if (ts <= o.challengedAt + params.resolutionPeriod()) {
                    throw new Revert("ResolutionStillOpen()");
                }
                s.cascade(index);
                return List.of();
            });
        }

        @Override
        public TxResult deleteFrom(long index) {
            String who = sender;
            return tx((s, ts) -> {
                requireSender(who, Accounts.GUARDIAN.address(), "NotGuardian()");
                Out o = s.out(index);
                if (o.status == OutputStatus.DELETED) {
                    throw new Revert("WrongStatus()");
                }
                if (o.status == OutputStatus.CHALLENGED) {
                    s.deleteUpheld(index, o);
                } else {
                    if (ts >= o.proposedAt + params.challengeWindow()) {
                        throw new Revert("OutputAlreadyFinal()");
                    }
                    s.cascade(index);
                }
                return List.of();
            });
        }

        @Override
        public TxResult proveWithdrawal(Withdrawal w, long outputIndex, List<Bytes32> proof) {
            return tx((s, ts) -> {
                if (s.paused) {
                    throw new Revert("IsPaused()");
                }
                if (proof.size() != WithdrawalTree.DEPTH) {
                    throw new Revert("BadProofLength()");
                }
                Out o = s.out(outputIndex);
                if (o.status != OutputStatus.PROPOSED) {
                    throw new Revert("OutputNotProvable()");
                }
                if (w.id() >= o.count) {
                    throw new Revert("IdOutOfRange()");
                }
                Bytes32 leaf = w.leaf();
                if (s.finalized.contains(leaf)) {
                    throw new Revert("AlreadyFinalized()");
                }
                if (!WithdrawalTree.verify(proof, o.root, leaf)) {
                    throw new Revert("InvalidProof()");
                }
                long[] previous = s.proofs.get(leaf);
                if (previous != null && s.outputs.get((int) previous[0]).status != OutputStatus.DELETED) {
                    throw new Revert("AlreadyProven()");
                }
                s.proofs.put(leaf, new long[] {outputIndex, ts});
                return List.of();
            });
        }

        @Override
        public TxResult finalizeWithdrawal(Withdrawal w) {
            return tx((s, ts) -> {
                if (s.paused) {
                    throw new Revert("IsPaused()");
                }
                Bytes32 leaf = w.leaf();
                if (s.finalized.contains(leaf)) {
                    throw new Revert("AlreadyFinalized()");
                }
                long[] p = s.proofs.get(leaf);
                if (p == null) {
                    throw new Revert("NotProven()");
                }
                if (!s.finalizable(p[0], ts)) {
                    throw new Revert("OutputNotFinalizable()");
                }
                if (w.amount().compareTo(s.totalLocked) > 0) {
                    throw new Revert("InsufficientLocked()");
                }
                s.finalized.add(leaf);
                s.totalLocked = s.totalLocked.subtract(w.amount());
                s.paid.merge(w.target(), w.amount(), BigInteger::add);
                String data = Hex.encode(Abi.encode(List.of(Abi.uint(w.amount()), new Abi.Bool(true))));
                return List.of(new TxResult.LogEntry("0xbridge", List.of(FINALIZED_TOPIC, leaf.hex()), data));
            });
        }

        @Override
        public TxResult pause() {
            String who = sender;
            return tx((s, ts) -> {
                requireSender(who, Accounts.GUARDIAN.address(), "NotGuardian()");
                s.paused = true;
                return List.of();
            });
        }

        @Override
        public TxResult unpause() {
            String who = sender;
            return tx((s, ts) -> {
                requireSender(who, Accounts.GUARDIAN.address(), "NotGuardian()");
                s.paused = false;
                return List.of();
            });
        }
    }

    public final class Gateway2 implements L2Reader, L2Writer {
        private final String sender;

        private Gateway2(String sender) {
            this.sender = sender;
        }

        @Override
        public long headNumber() {
            return l2Head().number();
        }

        @Override
        public Optional<Bytes32> blockHash(long number) {
            return number >= 0 && number < l2Chain.size() ? Optional.of(l2Chain.get((int) number).hash()) : Optional.empty();
        }

        @Override
        public List<WithdrawalEvent> withdrawalEvents(long fromBlock, long toBlock) {
            return l2Events.stream().filter(e -> e.l2Block() >= fromBlock && e.l2Block() <= toBlock).toList();
        }

        @Override
        public Bytes32 withdrawalRootAt(long block) {
            return l2Chain.get((int) block).rootAfter();
        }

        @Override
        public long withdrawalCountAt(long block) {
            return l2Chain.get((int) block).countAfter();
        }

        @Override
        public boolean isDepositProcessed(long depositId) {
            return processed.containsKey(depositId);
        }

        @Override
        public TxResult finalizeDeposit(long depositId, String to, BigInteger amount) {
            if (sender == null) {
                throw new GatewayException("read-only gateway: no signing key configured");
            }
            if (!sender.equals(Accounts.RELAYER.address())) {
                throw new GatewayException("reverted: NotRelayer()", "0x", null);
            }
            if (processed.containsKey(depositId)) {
                throw new GatewayException("reverted: DepositAlreadyProcessed(" + depositId + ")", "0x", null);
            }
            processed.put(depositId, new BigInteger[] {amount});
            processedTo.put(depositId, Hex.address(to));
            l2Balance.merge(Hex.address(to), amount, BigInteger::add);
            l2Supply = l2Supply.add(amount);
            mineL2(l2Tree.root());
            return new TxResult(Hex.encode(Keccak256.hash("l2tx-" + depositId)), true, l2Head().number(), List.of(), null);
        }
    }


    /** Raw access used by the lying proposer of the chaos scripts: the current L2 withdrawal tree (a copy). */
    public WithdrawalTree l2TreeCopy() {
        WithdrawalTree t = new WithdrawalTree();
        l2Tree.leaves().forEach(t::append);
        return t;
    }

}
