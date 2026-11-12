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
import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.core.model.WithdrawalEvent;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.port.L2Reader;
import dev.rollupbridge.core.port.L2Writer;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** L2 gateway over JSON-RPC: reads {@code L2Bridge} state (also at historical blocks) and mints deposits. */
public final class EvmL2Gateway implements L2Reader, L2Writer {
    static final String WITHDRAWAL_TOPIC = Hex.encode(
            Keccak256.hash("WithdrawalInitiated(uint256,address,address,uint256,bytes32,bytes32)"));

    private final JsonRpcClient rpc;
    private final String bridge;
    private final TxSender sender; // null when read-only

    public EvmL2Gateway(JsonRpcClient rpc, long chainId, String l2Bridge, String privateKeyOrNull) {
        this.rpc = rpc;
        this.bridge = Hex.address(l2Bridge);
        this.sender = privateKeyOrNull == null ? null : new TxSender(rpc, new Signer(privateKeyOrNull), chainId);
    }

    @Override
    public long headNumber() {
        return Hex.quantityToLong(rpc.call("eth_blockNumber").asText());
    }

    @Override
    public Optional<Bytes32> blockHash(long number) {
        JsonNode block = rpc.call("eth_getBlockByNumber", Hex.toQuantity(number), false);
        if (block == null || block.isNull()) {
            return Optional.empty();
        }
        return Optional.of(Bytes32.of(block.get("hash").asText()));
    }

    @Override
    public List<WithdrawalEvent> withdrawalEvents(long fromBlock, long toBlock) {
        if (fromBlock > toBlock) {
            return List.of();
        }
        var filter = JsonRpcClient.object();
        filter.put("fromBlock", Hex.toQuantity(fromBlock));
        filter.put("toBlock", Hex.toQuantity(toBlock));
        filter.put("address", bridge);
        filter.putArray("topics").add(WITHDRAWAL_TOPIC);
        List<WithdrawalEvent> out = new ArrayList<>();
        for (JsonNode log : rpc.call("eth_getLogs", filter)) {
            JsonNode t = log.get("topics");
            Abi.Words data = Abi.Words.of(log.get("data").asText());
            Withdrawal w = new Withdrawal(
                    Hex.quantityToLong(t.get(1).asText()),
                    Abi.topicToAddress(t.get(2).asText()),
                    Abi.topicToAddress(t.get(3).asText()),
                    data.uint(0));
            out.add(new WithdrawalEvent(
                    w,
                    data.bytes32(1),
                    data.bytes32(2),
                    Hex.quantityToLong(log.get("blockNumber").asText()),
                    Bytes32.of(log.get("blockHash").asText())));
        }
        return out;
    }

    @Override
    public Bytes32 withdrawalRootAt(long block) {
        return Abi.Words.of(call("withdrawalRoot()", Hex.toQuantity(block))).bytes32(0);
    }

    @Override
    public long withdrawalCountAt(long block) {
        return Abi.Words.of(call("withdrawalCount()", Hex.toQuantity(block))).uintAsLong(0);
    }

    @Override
    public boolean isDepositProcessed(long depositId) {
        return Abi.Words.of(call("processedDeposits(uint256)", "latest", Abi.uint(depositId))).bool(0);
    }

    @Override
    public TxResult finalizeDeposit(long depositId, String to, BigInteger amount) {
        if (sender == null) {
            throw new GatewayException("read-only gateway: no signing key configured");
        }
        return sender.send(
                bridge,
                BigInteger.ZERO,
                Abi.encodeCall(
                        "finalizeDeposit(uint256,address,uint256)", Abi.uint(depositId), Abi.address(to), Abi.uint(amount)));
    }

    /** User action used by tests and the demo: burn on L2 and start a withdrawal (needs a signing key = the user). */
    public TxResult initiateWithdrawal(String target, BigInteger amount) {
        if (sender == null) {
            throw new GatewayException("read-only gateway: no signing key configured");
        }
        return sender.send(
                bridge,
                BigInteger.ZERO,
                Abi.encodeCall("initiateWithdrawal(address,uint256)", Abi.address(target), Abi.uint(amount)));
    }

    private String call(String signature, String block, Abi.Value... args) {
        var tx = JsonRpcClient.object();
        tx.put("to", bridge);
        tx.put("data", Hex.encode(Abi.encodeCall(signature, args)));
        return rpc.call("eth_call", tx, block).asText();
    }

    private String call(String signature, String block) {
        return call(signature, block, new Abi.Value[0]);
    }
}
