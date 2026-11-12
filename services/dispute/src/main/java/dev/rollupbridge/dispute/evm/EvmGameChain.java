package dev.rollupbridge.dispute.evm;

import com.fasterxml.jackson.databind.JsonNode;
import dev.rollupbridge.adapters.rpc.JsonRpcClient;
import dev.rollupbridge.adapters.rpc.Signer;
import dev.rollupbridge.adapters.rpc.TxSender;
import dev.rollupbridge.core.Abi;
import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Hex;
import dev.rollupbridge.core.Keccak256;
import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.dispute.game.GameChain;
import dev.rollupbridge.dispute.game.GameException;
import dev.rollupbridge.dispute.game.GameView;
import dev.rollupbridge.dispute.game.GameView.Phase;
import dev.rollupbridge.dispute.game.GameView.Resolution;
import dev.rollupbridge.dispute.vm.Program;
import dev.rollupbridge.dispute.vm.VmState;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link GameChain} over JSON-RPC: every move is a signed transaction to a deployed {@code BisectionGame}. Each
 * participant address has its own key (test keys only). A move the contract reverts is translated back to a
 * {@link GameException} with the custom-error name, so callers see the same outcomes as with the in-memory model.
 */
public final class EvmGameChain implements GameChain {
    private static final Map<String, String> ERROR_NAMES = new HashMap<>();

    static {
        for (String sig : List.of(
                "ZeroParameter()", "ParameterTooLarge()", "UnknownGame(uint256)", "WrongPhase(uint8)",
                "WrongBond(uint256,uint256)", "ZeroSteps()", "NotProposer()", "NotChallenger()", "MoveTimeExpired()",
                "TimeoutNotReached()", "WrongStep(uint64,uint64)", "ProgramMismatch()", "PreStateMismatch()",
                "NothingToWithdraw()", "TransferFailed()", "ReentrancyGuardReentrantCall()")) {
            ERROR_NAMES.put(Hex.encode(Abi.selector(sig)), sig.substring(0, sig.indexOf('(')));
        }
    }

    private static final String STEP_SIGNATURE = "step(uint256,bytes,(uint64,uint64[4]))";
    private static final String GAME_CREATED_TOPIC =
            Hex.encode(Keccak256.hash("GameCreated(uint256,address,bytes32,uint64,bytes32)"));

    private final JsonRpcClient rpc;
    private final long chainId;
    private final String contract;
    private final Map<String, TxSender> senders = new HashMap<>();
    private final BigInteger proposerBond;
    private final BigInteger challengerBond;
    private final long moveTimeout;

    /** @param keysByAddress private key of every account that may send moves (lower-case address to 0x key) */
    public EvmGameChain(JsonRpcClient rpc, long chainId, String contract, Map<String, String> keysByAddress) {
        this.rpc = rpc;
        this.chainId = chainId;
        this.contract = Hex.address(contract);
        keysByAddress.forEach((address, key) ->
                senders.put(Hex.address(address), new TxSender(rpc, new Signer(key), chainId)));
        this.proposerBond = callUint("PROPOSER_BOND()");
        this.challengerBond = callUint("CHALLENGER_BOND()");
        this.moveTimeout = callUint("MOVE_TIMEOUT()").longValueExact();
    }

    public String contractAddress() {
        return contract;
    }

    // ----- moves -----

    @Override
    public long createGame(String sender, Bytes32 initialHash, long steps, Bytes32 claimedHash) {
        TxResult tx = send(sender, proposerBond,
                Abi.encodeCall("createGame(bytes32,uint64,bytes32)", Abi.bytes32(initialHash), Abi.uint(steps), Abi.bytes32(claimedHash)));
        for (TxResult.LogEntry log : tx.logs()) {
            if (log.address().equalsIgnoreCase(contract) && log.topics().get(0).equalsIgnoreCase(GAME_CREATED_TOPIC)) {
                return Hex.quantityToLong(log.topics().get(1));
            }
        }
        throw new IllegalStateException("no GameCreated event in " + tx.txHash());
    }

    @Override
    public void challenge(String sender, long id) {
        send(sender, challengerBond, Abi.encodeCall("challenge(uint256)", Abi.uint(id)));
    }

    @Override
    public void bisect(String sender, long id, long step, Bytes32 midHash) {
        send(sender, BigInteger.ZERO,
                Abi.encodeCall("bisect(uint256,uint64,bytes32)", Abi.uint(id), Abi.uint(step), Abi.bytes32(midHash)));
    }

    @Override
    public void respond(String sender, long id, boolean agree) {
        send(sender, BigInteger.ZERO, Abi.encodeCall("respond(uint256,bool)", Abi.uint(id), new Abi.Bool(agree)));
    }

    @Override
    public void step(String sender, long id, Program program, VmState preState) {
        send(sender, BigInteger.ZERO, encodeStep(id, program, preState));
    }

    @Override
    public void claimTimeout(String sender, long id) {
        send(sender, BigInteger.ZERO, Abi.encodeCall("claimTimeout(uint256)", Abi.uint(id)));
    }

    @Override
    public BigInteger withdraw(String sender) {
        BigInteger owed = credits(sender);
        send(sender, BigInteger.ZERO, Abi.encodeCall("withdraw()"));
        return owed;
    }

    /**
     * {@code step(uint256 id, bytes program, VMState pre)} by hand: the core ABI codec has no dynamic {@code bytes}.
     * Layout: selector | id | offset of the bytes (7 words in) | the 5 inline words of the static struct | the bytes
     * (length word, then the data padded to a multiple of 32).
     */
    static byte[] encodeStep(long id, Program program, VmState s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(Abi.selector(STEP_SIGNATURE));
        out.writeBytes(word(BigInteger.valueOf(id)));
        out.writeBytes(word(BigInteger.valueOf(7 * 32L)));
        for (long v : new long[] {s.pc(), s.r0(), s.r1(), s.r2(), s.r3()}) {
            out.writeBytes(word(new BigInteger(Long.toUnsignedString(v))));
        }
        byte[] code = program.code();
        out.writeBytes(word(BigInteger.valueOf(code.length)));
        out.writeBytes(code);
        out.writeBytes(new byte[(32 - code.length % 32) % 32]);
        return out.toByteArray();
    }

    private static byte[] word(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int start = raw[0] == 0 && raw.length > 1 ? 1 : 0;
        System.arraycopy(raw, start, out, 32 - (raw.length - start), raw.length - start);
        return out;
    }

    private TxResult send(String sender, BigInteger value, byte[] data) {
        TxSender s = senders.get(Hex.address(sender));
        if (s == null) {
            throw new IllegalArgumentException("no key for " + sender);
        }
        try {
            TxResult tx = s.send(contract, value, data);
            if (!tx.success()) {
                throw new GameException("TransactionFailed", tx.txHash());
            }
            return tx;
        } catch (GatewayException e) {
            throw translate(e);
        }
    }

    static GameException translate(GatewayException e) {
        String data = e.revertData();
        if (data != null && data.length() >= 10) {
            String name = ERROR_NAMES.get(data.substring(0, 10).toLowerCase(Locale.ROOT));
            if (name != null) {
                return new GameException(name, data);
            }
        }
        throw e; // not a contract revert (transport problem, bug): do not disguise it as a refused move
    }

    // ----- views -----

    @Override
    public GameView game(long id) {
        Abi.Words w = Abi.Words.of(call("getGame(uint256)", Abi.uint(id)));
        return new GameView(
                w.address(0),
                w.address(1),
                Phase.values()[w.uint(2).intValueExact()],
                Resolution.values()[w.uint(3).intValueExact()],
                w.bool(4),
                w.uintAsLong(5),
                w.uintAsLong(6),
                w.uintAsLong(7),
                w.bytes32(8),
                w.bytes32(9),
                w.bytes32(10));
    }

    @Override
    public long gameCount() {
        return callUint("gameCount()").longValueExact();
    }

    @Override
    public BigInteger credits(String account) {
        return Abi.Words.of(call("credits(address)", Abi.address(account))).uint(0);
    }

    @Override
    public BigInteger proposerBond() {
        return proposerBond;
    }

    @Override
    public BigInteger challengerBond() {
        return challengerBond;
    }

    @Override
    public long moveTimeout() {
        return moveTimeout;
    }

    @Override
    public long now() {
        JsonNode latest = rpc.call("eth_getBlockByNumber", "latest", false);
        return Hex.quantityToLong(latest.get("timestamp").asText());
    }

    @Override
    public void advanceTime(long seconds) {
        rpc.call("evm_increaseTime", seconds);
        rpc.call("anvil_mine", "0x1");
    }

    /** Forces the timestamp of the NEXT block (Anvil), so a test can drive two chains with one clock. */
    public void setNextBlockTimestamp(long timestamp) {
        rpc.call("evm_setNextBlockTimestamp", timestamp);
    }

    public long chainId() {
        return chainId;
    }

    private String call(String signature, Abi.Value... args) {
        var tx = JsonRpcClient.object();
        tx.put("to", contract);
        tx.put("data", Hex.encode(Abi.encodeCall(signature, args)));
        try {
            return rpc.call("eth_call", tx, "latest").asText();
        } catch (GatewayException e) {
            throw translate(e);
        }
    }

    private BigInteger callUint(String signature) {
        return Abi.Words.of(call(signature)).uint(0);
    }
}
