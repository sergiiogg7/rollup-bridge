package dev.rollupbridge.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.rollupbridge.adapters.evm.EvmL1Gateway;
import dev.rollupbridge.adapters.evm.EvmL2Gateway;
import dev.rollupbridge.adapters.rpc.JsonRpcClient;
import dev.rollupbridge.adapters.rpc.Signer;
import dev.rollupbridge.adapters.rpc.TxSender;
import dev.rollupbridge.core.Abi;
import dev.rollupbridge.core.Hex;
import dev.rollupbridge.core.model.TxResult;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Both chains plus a fresh deployment of all contracts on them. Contracts are deployed from the Foundry build
 * artifacts ({@code contracts/out}), so the Java tests always exercise the real compiled Solidity.
 */
public final class Bridge {
    public static final BigInteger ETH = BigInteger.TEN.pow(18);

    /** Contract parameters used by the scenarios: short windows so that tests can fast-forward through them. */
    public record Params(
            long challengeWindow,
            long resolutionPeriod,
            BigInteger proposerBond,
            BigInteger challengerBond,
            BigInteger maxDeposit,
            BigInteger maxTotalLocked) {
        public static Params defaults() {
            return new Params(100, 500, ETH, ETH.divide(BigInteger.TWO), ETH.multiply(BigInteger.valueOf(100)), ETH.multiply(BigInteger.valueOf(1000)));
        }
    }

    public final Anvil l1Node;
    public final Anvil l2Node;
    public final Params params;
    public final String outputOracle;
    public final String l1Bridge;
    public final String l2Bridge;
    public final String token;
    public final long l1DeployBlock;

    private Bridge(Anvil l1, Anvil l2, Params params, String oracle, String l1Bridge, String l2Bridge, String token, long l1DeployBlock) {
        this.l1Node = l1;
        this.l2Node = l2;
        this.params = params;
        this.outputOracle = oracle;
        this.l1Bridge = l1Bridge;
        this.l2Bridge = l2Bridge;
        this.token = token;
        this.l1DeployBlock = l1DeployBlock;
    }

    /** Deploys oracle + L1 bridge on L1 and L2 bridge (+ token) on L2, using the standard role accounts. */
    public static Bridge deploy(Anvil l1, Anvil l2, Params p) {
        Path out = contractsOut();
        TxSender l1Deployer = new TxSender(l1.rpc(), new Signer(Accounts.DEPLOYER.privateKey()), l1.chainId());
        TxSender l2Deployer = new TxSender(l2.rpc(), new Signer(Accounts.DEPLOYER.privateKey()), l2.chainId());

        String oracle = create(l1Deployer, out, "OutputOracle", Abi.encode(List.of(
                Abi.address(Accounts.DEPLOYER.address()),
                Abi.address(Accounts.PROPOSER.address()),
                Abi.address(Accounts.GUARDIAN.address()),
                Abi.uint(p.challengeWindow()),
                Abi.uint(p.resolutionPeriod()),
                Abi.uint(p.proposerBond()),
                Abi.uint(p.challengerBond()))));
        String bridge = create(l1Deployer, out, "L1Bridge", Abi.encode(List.of(
                Abi.address(Accounts.DEPLOYER.address()),
                Abi.address(oracle),
                Abi.uint(p.maxDeposit()),
                Abi.uint(p.maxTotalLocked()))));
        String l2Bridge = create(l2Deployer, out, "L2Bridge", Abi.encode(List.of(
                Abi.address(Accounts.DEPLOYER.address()), Abi.address(Accounts.RELAYER.address()))));
        String token = Abi.Words.of(ethCall(l2.rpc(), l2Bridge, "TOKEN()")).address(0);
        long deployBlock = new EvmL1Gateway(l1.rpc(), l1.chainId(), bridge, oracle, null).headNumber();
        return new Bridge(l1, l2, p, oracle, bridge, l2Bridge, token, deployBlock);
    }

    private static String create(TxSender sender, Path out, String name, byte[] constructorArgs) {
        try {
            JsonNode artifact = new ObjectMapper().readTree(out.resolve(name + ".sol").resolve(name + ".json").toFile());
            byte[] code = Hex.decode(artifact.get("bytecode").get("object").asText());
            byte[] init = new byte[code.length + constructorArgs.length];
            System.arraycopy(code, 0, init, 0, code.length);
            System.arraycopy(constructorArgs, 0, init, code.length, constructorArgs.length);
            TxResult tx = sender.send(null, BigInteger.ZERO, init);
            if (!tx.success() || tx.contractAddress() == null) {
                throw new IllegalStateException("deployment of " + name + " failed");
            }
            return Hex.address(tx.contractAddress());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read artifact of " + name + " (run `forge build` in contracts/)", e);
        }
    }

    /** Finds {@code contracts/out}, building it with forge if it does not exist yet. */
    public static Path contractsOut() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("contracts/foundry.toml"))) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("contracts/foundry.toml not found above " + Path.of("").toAbsolutePath());
        }
        Path out = dir.resolve("contracts/out");
        if (!Files.exists(out.resolve("L1Bridge.sol/L1Bridge.json"))) {
            try {
                Process p = new ProcessBuilder("forge", "build").directory(dir.resolve("contracts").toFile())
                        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (p.waitFor() != 0) {
                    throw new IllegalStateException("forge build failed");
                }
            } catch (IOException | InterruptedException e) {
                throw new IllegalStateException("cannot run forge build", e);
            }
        }
        return out;
    }

    // ----- gateways per role -----

    public EvmL1Gateway l1(Accounts role) {
        return new EvmL1Gateway(l1Node.rpc(), l1Node.chainId(), l1Bridge, outputOracle, role == null ? null : role.privateKey());
    }

    public EvmL2Gateway l2(Accounts role) {
        return new EvmL2Gateway(l2Node.rpc(), l2Node.chainId(), l2Bridge, role == null ? null : role.privateKey());
    }

    // ----- user actions and observers -----

    public TxResult deposit(Accounts user, Accounts to, BigInteger wei) {
        return l1(user).depositEth(to.address(), wei);
    }

    public TxResult withdraw(Accounts user, String target, BigInteger wei) {
        return l2(user).initiateWithdrawal(target, wei);
    }

    public BigInteger l1Balance(String address) {
        return Hex.quantityToBigInteger(l1Node.rpc().call("eth_getBalance", address, "latest").asText());
    }

    /** bETH balance on L2. */
    public BigInteger l2Balance(String address) {
        return Abi.Words.of(ethCall(l2Node.rpc(), token, "balanceOf(address)", Abi.address(address))).uint(0);
    }

    public BigInteger l2TotalSupply() {
        return Abi.Words.of(ethCall(l2Node.rpc(), token, "totalSupply()")).uint(0);
    }

    /** {@code L1Bridge.totalLocked()}. */
    public BigInteger l1TotalLocked() {
        return Abi.Words.of(ethCall(l1Node.rpc(), l1Bridge, "totalLocked()")).uint(0);
    }

    public BigInteger oracleCredits(String account) {
        return Abi.Words.of(ethCall(l1Node.rpc(), outputOracle, "credits(address)", Abi.address(account))).uint(0);
    }

    private static String ethCall(JsonRpcClient rpc, String to, String signature, Abi.Value... args) {
        var tx = JsonRpcClient.object();
        tx.put("to", to);
        tx.put("data", Hex.encode(Abi.encodeCall(signature, args)));
        return rpc.call("eth_call", tx, "latest").asText();
    }
}
