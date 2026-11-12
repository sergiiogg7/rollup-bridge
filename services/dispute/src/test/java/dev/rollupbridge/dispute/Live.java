package dev.rollupbridge.dispute;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rollupbridge.adapters.rpc.Signer;
import dev.rollupbridge.adapters.rpc.TxSender;
import dev.rollupbridge.core.Abi;
import dev.rollupbridge.core.Hex;
import dev.rollupbridge.core.model.TxResult;
import dev.rollupbridge.dispute.evm.EvmGameChain;
import dev.rollupbridge.dispute.vm.Program;
import dev.rollupbridge.testkit.Accounts;
import dev.rollupbridge.testkit.Anvil;
import dev.rollupbridge.testkit.Bridge;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Shared live-chain fixture: ONE native Anvil per JVM (any free port) and deployers for the dispute contracts. */
final class Live {
    static final long CHAIN_ID = 31337;
    static final long MOVE_TIMEOUT = 3600;
    static final BigInteger PROPOSER_BOND = BigInteger.TEN.pow(18);
    static final BigInteger CHALLENGER_BOND = PROPOSER_BOND.divide(BigInteger.TWO);

    private static Anvil anvil;

    private Live() {}

    /** The shared Anvil; skips (does not fail) the calling test when no {@code anvil} binary is on the PATH. */
    static synchronized Anvil anvil() {
        assumeTrue(Anvil.isAvailable(), "anvil not found on PATH: live tests skipped");
        if (anvil == null) {
            anvil = Anvil.start(0, CHAIN_ID);
        }
        return anvil;
    }

    static TxSender deployer(Anvil a) {
        return new TxSender(a.rpc(), new Signer(Accounts.DEPLOYER.privateKey()), a.chainId());
    }

    /** Deploys a contract from its Foundry artifact ({@code contracts/out}); returns the address. */
    static String deploy(Anvil a, String sourceFile, String contract, byte[] constructorArgs) {
        try {
            Path out = Bridge.contractsOut();
            JsonNode artifact = new ObjectMapper().readTree(out.resolve(sourceFile).resolve(contract + ".json").toFile());
            byte[] code = Hex.decode(artifact.get("bytecode").get("object").asText());
            byte[] init = new byte[code.length + constructorArgs.length];
            System.arraycopy(code, 0, init, 0, code.length);
            System.arraycopy(constructorArgs, 0, init, code.length, constructorArgs.length);
            TxResult tx = deployer(a).send(null, BigInteger.ZERO, init);
            if (!tx.success() || tx.contractAddress() == null) {
                throw new IllegalStateException("deployment of " + contract + " failed");
            }
            return Hex.address(tx.contractAddress());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the artifact of " + contract + " (run `forge build` in contracts/)", e);
        }
    }

    /** A fresh {@code BisectionGame} committed to {@code program}, with test keys for every account used by the tests. */
    static EvmGameChain deployGame(Anvil a, Program program) {
        String address = deploy(a, "BisectionGame.sol", "BisectionGame", Abi.encode(List.of(
                Abi.bytes32(program.hash()),
                Abi.uint(MOVE_TIMEOUT),
                Abi.uint(PROPOSER_BOND),
                Abi.uint(CHALLENGER_BOND))));
        return new EvmGameChain(a.rpc(), a.chainId(), address, keys());
    }

    static Map<String, String> keys() {
        return Map.of(
                Accounts.PROPOSER.address(), Accounts.PROPOSER.privateKey(),
                Accounts.CHALLENGER.address(), Accounts.CHALLENGER.privateKey(),
                Accounts.ALICE.address(), Accounts.ALICE.privateKey(),
                Accounts.MALLORY.address(), Accounts.MALLORY.privateKey());
    }
}
