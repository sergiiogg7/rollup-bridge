package dev.rollupbridge.adapters.evm;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rollupbridge.adapters.rpc.JsonRpcClient;
import dev.rollupbridge.adapters.rpc.Signer;
import dev.rollupbridge.adapters.rpc.TxSender;
import dev.rollupbridge.core.Abi;
import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Hex;
import dev.rollupbridge.core.Keccak256;
import dev.rollupbridge.core.Withdrawal;
import dev.rollupbridge.core.model.DepositEvent;
import dev.rollupbridge.core.model.OutputRecord;
import dev.rollupbridge.core.model.OutputStatus;
import dev.rollupbridge.core.model.ProofInfo;
import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L1Writer;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * L1 gateway over JSON-RPC: reads {@code L1Bridge} / {@code OutputOracle} state and sends transactions as the
 * role that owns the signing key. Read-only instances (no signer) are fine for the challenger watch loop and
 * the status command.
 */
public final class EvmL1Gateway implements L1Reader, L1Writer {
    static final String DEPOSIT_TOPIC =
            Hex.encode(Keccak256.hash("DepositInitiated(uint256,address,address,uint256)"));

    private final JsonRpcClient rpc;
    private final String bridge;
    private final String oracle;
    private final TxSender sender; // null when read-only

    public EvmL1Gateway(JsonRpcClient rpc, long chainId, String l1Bridge, String outputOracle, String privateKeyOrNull) {
        this.rpc = rpc;
        this.bridge = Hex.address(l1Bridge);
        this.oracle = Hex.address(outputOracle);
        this.sender = privateKeyOrNull == null ? null : new TxSender(rpc, new Signer(privateKeyOrNull), chainId);
    }

    // ---------------------------------------------------------------------------------------------
    // L1Reader
    // ---------------------------------------------------------------------------------------------

    @Override
    public long headNumber() {
        return Hex.quantityToLong(rpc.call("eth_blockNumber").asText());
    }

    @Override
    public Optional<Bytes32> canonicalHash(long number) {
        JsonNode block = rpc.call("eth_getBlockByNumber", Hex.toQuantity(number), false);
        if (block == null || block.isNull()) {
            return Optional.empty();
        }
        return Optional.of(Bytes32.of(block.get("hash").asText()));
    }

    @Override
    public long headTimestamp() {
        JsonNode block = rpc.call("eth_getBlockByNumber", "latest", false);
        return Hex.quantityToLong(block.get("timestamp").asText());
    }

    @Override
    public List<DepositEvent> depositEvents(long fromBlock, long toBlock) {
        if (fromBlock > toBlock) {
            return List.of();
        }
        var filter = JsonRpcClient.object();
        filter.put("fromBlock", Hex.toQuantity(fromBlock));
        filter.put("toBlock", Hex.toQuantity(toBlock));
        filter.put("address", bridge);
        filter.putArray("topics").add(DEPOSIT_TOPIC);
        List<DepositEvent> out = new ArrayList<>();
        for (JsonNode log : rpc.call("eth_getLogs", filter)) {
            JsonNode topics = log.get("topics");
            long id = Hex.quantityToLong(topics.get(1).asText());
            String from = Abi.topicToAddress(topics.get(2).asText());
            String to = Abi.topicToAddress(topics.get(3).asText());
            BigInteger amount = new Abi.Words(Hex.decode(log.get("data").asText())).uint(0);
            out.add(new DepositEvent(
                    id,
                    from,
                    to,
                    amount,
                    Hex.quantityToLong(log.get("blockNumber").asText()),
                    Bytes32.of(log.get("blockHash").asText())));
        }
        return out;
    }

    @Override
    public long outputCount() {
        return words(callOracle("outputCount()")).uintAsLong(0);
    }

    @Override
    public OutputRecord output(long index) {
        Abi.Words w = words(callOracle("getOutput(uint256)", Abi.uint(index)));
        return new OutputRecord(
                index,
                w.bytes32(0),
                w.bytes32(1),
                w.uintAsLong(2),
                w.uintAsLong(3),
                w.uintAsLong(4),
                OutputStatus.values()[(int) w.uintAsLong(5)],
                w.address(6),
                w.address(7),
                w.uintAsLong(8),
                w.bool(9));
    }

    @Override
    public Optional<OutputRecord> latestLiveOutput() {
        Abi.Words w = words(callOracle("latestLive()"));
        return w.bool(0) ? Optional.of(output(w.uintAsLong(1))) : Optional.empty();
    }

    @Override
    public boolean isFinalizable(long outputIndex) {
        return words(callOracle("isFinalizable(uint256)", Abi.uint(outputIndex))).bool(0);
    }

    @Override
    public ProofInfo proof(Bytes32 leaf) {
        Abi.Words w = words(call(bridge, "proofs(bytes32)", Abi.bytes32(leaf)));
        return w.bool(2) ? new ProofInfo(true, w.uintAsLong(0), w.uintAsLong(1)) : ProofInfo.NONE;
    }

    @Override
    public boolean isFinalized(Bytes32 leaf) {
        return words(call(bridge, "finalized(bytes32)", Abi.bytes32(leaf))).bool(0);
    }

    @Override
    public boolean isPaused() {
        return words(call(bridge, "paused()")).bool(0);
    }

    @Override
    public long challengeWindow() {
        return words(callOracle("CHALLENGE_WINDOW()")).uintAsLong(0);
    }

    @Override
    public BigInteger proposerBond() {
        return words(callOracle("PROPOSER_BOND()")).uint(0);
    }

    @Override
    public BigInteger challengerBond() {
        return words(callOracle("CHALLENGER_BOND()")).uint(0);
    }

    @Override
    public BigInteger claimable(String account) {
        return words(call(bridge, "claimable(address)", Abi.address(account))).uint(0);
    }

    // ---------------------------------------------------------------------------------------------
    // L1Writer
    // ---------------------------------------------------------------------------------------------

    @Override
    public TxResult proposeOutput(Bytes32 root, Bytes32 l2BlockHash, long l2BlockNumber, long withdrawalCount) {
        return send(
                oracle,
                proposerBond(),
                Abi.encodeCall(
                        "proposeOutput(bytes32,bytes32,uint64,uint64)",
                        Abi.bytes32(root),
                        Abi.bytes32(l2BlockHash),
                        Abi.uint(l2BlockNumber),
                        Abi.uint(withdrawalCount)));
    }

    @Override
    public TxResult challengeOutput(long index) {
        return send(oracle, challengerBond(), Abi.encodeCall("challengeOutput(uint256)", Abi.uint(index)));
    }

    @Override
    public TxResult resolveChallenge(long index, boolean proposerWasWrong) {
        return send(
                oracle,
                BigInteger.ZERO,
                Abi.encodeCall("resolveChallenge(uint256,bool)", Abi.uint(index), new Abi.Bool(proposerWasWrong)));
    }

    @Override
    public TxResult expireChallenge(long index) {
        return send(oracle, BigInteger.ZERO, Abi.encodeCall("expireChallenge(uint256)", Abi.uint(index)));
    }

    @Override
    public TxResult deleteFrom(long index) {
        return send(oracle, BigInteger.ZERO, Abi.encodeCall("deleteFrom(uint256)", Abi.uint(index)));
    }

    @Override
    public TxResult proveWithdrawal(Withdrawal w, long outputIndex, List<Bytes32> proof) {
        return send(
                bridge,
                BigInteger.ZERO,
                Abi.encodeCall(
                        "proveWithdrawal((uint256,address,address,uint256),uint256,bytes32[])",
                        w.asTuple(),
                        Abi.uint(outputIndex),
                        new Abi.B32Array(proof)));
    }

    @Override
    public TxResult finalizeWithdrawal(Withdrawal w) {
        return send(
                bridge,
                BigInteger.ZERO,
                Abi.encodeCall("finalizeWithdrawal((uint256,address,address,uint256))", w.asTuple()));
    }

    @Override
    public TxResult pause() {
        return send(bridge, BigInteger.ZERO, Abi.encodeCall("pause()"));
    }

    @Override
    public TxResult unpause() {
        return send(bridge, BigInteger.ZERO, Abi.encodeCall("unpause()"));
    }

    /** Plain deposit helper used by tests and the demo (a user action, not a service action). */
    public TxResult depositEth(String to, BigInteger wei) {
        return send(bridge, wei, Abi.encodeCall("depositETH(address)", Abi.address(to)));
    }

    // ---------------------------------------------------------------------------------------------

    private TxResult send(String to, BigInteger value, byte[] data) {
        if (sender == null) {
            throw new GatewayException("read-only gateway: no signing key configured");
        }
        return sender.send(to, value, data);
    }

    private String callOracle(String signature, Abi.Value... args) {
        return call(oracle, signature, args);
    }

    private String call(String to, String signature, Abi.Value... args) {
        var tx = JsonRpcClient.object();
        tx.put("to", to);
        tx.put("data", Hex.encode(Abi.encodeCall(signature, args)));
        return rpc.call("eth_call", tx, "latest").asText();
    }

    private static Abi.Words words(String hex) {
        return Abi.Words.of(hex);
    }
}
