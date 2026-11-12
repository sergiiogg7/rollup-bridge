package dev.rollupbridge.app;

import com.sun.net.httpserver.HttpServer;
import dev.rollupbridge.adapters.evm.EvmL1Gateway;
import dev.rollupbridge.adapters.evm.EvmL2Gateway;
import dev.rollupbridge.adapters.rpc.JsonRpcClient;
import dev.rollupbridge.adapters.store.SqliteStore;
import dev.rollupbridge.services.Challenger;
import dev.rollupbridge.services.DepositRelayer;
import dev.rollupbridge.services.Guardian;
import dev.rollupbridge.services.OutputVerifier;
import dev.rollupbridge.services.Proposer;
import dev.rollupbridge.services.Service;
import dev.rollupbridge.services.ServiceRunner;
import dev.rollupbridge.services.WithdrawalRelayer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point. One process per role in production; {@code all} runs every role in one JVM for demos.
 *
 * <pre>
 *   deposit-relayer | proposer | withdrawal-relayer | challenger     run one service (add --once for a single tick)
 *   guardian watch | resolve <index> | pause | unpause             the trusted party
 *   all                                                               every service in one JVM (demo)
 *   status                                                            print the current state and exit
 * </pre>
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        Config cfg = Config.fromEnv();
        Files.createDirectories(cfg.dbDir());
        boolean once = List.of(args).contains("--once");
        String command = args[0];

        switch (command) {
            case "deposit-relayer" -> run(List.of(depositRelayer(cfg, depositStore(cfg))), cfg, once);
            case "proposer" -> run(List.of(proposer(cfg)), cfg, once);
            case "withdrawal-relayer" -> run(List.of(withdrawalRelayer(cfg, withdrawalStore(cfg))), cfg, once);
            case "challenger" -> run(List.of(challenger(cfg, new SqliteStore("jdbc:sqlite:" + cfg.dbDir().resolve("challenger.db").toAbsolutePath()))), cfg, once);
            case "guardian" -> guardian(cfg, args);
            case "all" -> all(cfg, once);
            case "status" -> System.out.println(status(cfg, depositStore(cfg), withdrawalStore(cfg)));
            case "demo-attack" -> demoAttack(cfg, args);
            default -> usage();
        }
    }

    // ----- wiring (manual constructor injection) -----

    private static EvmL1Gateway l1(Config c, String key) {
        return new EvmL1Gateway(new JsonRpcClient(c.l1Rpc()), c.l1ChainId(), c.l1Bridge(), c.outputOracle(), key);
    }

    private static EvmL2Gateway l2(Config c, String key) {
        return new EvmL2Gateway(new JsonRpcClient(c.l2Rpc()), c.l2ChainId(), c.l2Bridge(), key);
    }

    private static SqliteStore depositStore(Config c) {
        return new SqliteStore("jdbc:sqlite:" + c.dbDir().resolve("deposit-relayer.db").toAbsolutePath());
    }

    private static SqliteStore withdrawalStore(Config c) {
        return new SqliteStore("jdbc:sqlite:" + c.dbDir().resolve("withdrawal-relayer.db").toAbsolutePath());
    }

    private static Service depositRelayer(Config c, SqliteStore store) {
        return new DepositRelayer(
                l1(c, null), l2(c, null), l2(c, c.require(c.relayerKey(), "RELAYER_KEY")), store,
                new DepositRelayer.Config(c.depositConfirmations(), c.l1StartBlock(), Math.max(20, c.depositConfirmations() + 5)));
    }

    private static Service proposer(Config c) {
        return new Proposer(l1(c, null), l1(c, c.require(c.proposerKey(), "PROPOSER_KEY")), l2(c, null), new Proposer.Config(0));
    }

    private static Service withdrawalRelayer(Config c, SqliteStore store) {
        return new WithdrawalRelayer(l1(c, null), l1(c, c.require(c.relayerKey(), "RELAYER_KEY")), l2(c, null), store);
    }

    private static Service challenger(Config c, SqliteStore store) {
        return new Challenger(l1(c, null), l1(c, c.require(c.challengerKey(), "CHALLENGER_KEY")), new OutputVerifier(l2(c, null)), store);
    }

    private static Guardian guardianService(Config c) {
        return new Guardian(l1(c, null), l1(c, c.require(c.guardianKey(), "GUARDIAN_KEY")), new OutputVerifier(l2(c, null)));
    }

    // ----- commands -----

    /**
     * DEMO ONLY (uses the testkit): the proposer key posts an output that contains a fabricated withdrawal paying
     * the attacker, and the attacker immediately proves it. Usage: demo-attack fake-leaf <amountWei>
     * Needs PROPOSER_KEY and MALLORY_KEY (Anvil test keys).
     */
    private static void demoAttack(Config cfg, String[] args) {
        String malloryKey = cfg.require(System.getenv("MALLORY_KEY"), "MALLORY_KEY");
        String mallory = new dev.rollupbridge.adapters.rpc.Signer(malloryKey).address();
        java.math.BigInteger amount = new java.math.BigInteger(args.length > 2 ? args[2] : "4000000000000000000");
        var attacker = new dev.rollupbridge.testkit.MaliciousProposer(l2(cfg, null), l1(cfg, cfg.require(cfg.proposerKey(), "PROPOSER_KEY")));
        var posted = attacker.propose(dev.rollupbridge.testkit.MaliciousProposer.Mode.FAKE_LEAF, mallory, amount);
        long index = l1(cfg, null).outputCount() - 1;
        System.out.println("malicious output #" + index + " posted in tx " + posted.tx().txHash());
        var proof = l1(cfg, malloryKey).proveWithdrawal(posted.fakeWithdrawal(), index, posted.fakeProof());
        if (proof.success()) {
            System.out.println("the fabricated withdrawal #" + posted.fakeWithdrawal().id() + " (" + amount + " wei to " + mallory + ") was PROVEN against it");
        } else {
            System.out.println("the proof transaction was mined but failed: the challenger was faster than the attacker");
        }
    }

    private static void guardian(Config cfg, String[] args) {
        Guardian g = guardianService(cfg);
        String sub = args.length > 1 ? args[1] : "watch";
        switch (sub) {
            case "watch" -> run(List.of(g), cfg, List.of(args).contains("--once"));
            case "resolve" -> System.out.println("proposer was wrong: " + g.resolve(Long.parseLong(args[2])));
            case "pause" -> g.pause();
            case "unpause" -> g.unpause();
            default -> usage();
        }
    }

    private static void all(Config cfg, boolean once) throws IOException {
        SqliteStore deposits = depositStore(cfg);
        SqliteStore withdrawals = withdrawalStore(cfg);
        SqliteStore challengerDb = new SqliteStore("jdbc:sqlite:" + cfg.dbDir().resolve("challenger.db").toAbsolutePath());
        List<Service> services = List.of(
                depositRelayer(cfg, deposits),
                proposer(cfg),
                withdrawalRelayer(cfg, withdrawals),
                challenger(cfg, challengerDb),
                guardianService(cfg));
        if (cfg.statusPort() > 0) {
            serveStatus(cfg, deposits, withdrawals);
        }
        run(services, cfg, once);
    }

    private static void run(List<Service> services, Config cfg, boolean once) {
        if (once) {
            services.forEach(Service::tick);
            return;
        }
        List<Thread> threads = new ArrayList<>();
        for (Service s : services) {
            Thread t = new Thread(new ServiceRunner(s, Duration.ofMillis(s instanceof Challenger ? cfg.challengerPollMillis() : cfg.pollMillis())), s.name());
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static String status(Config cfg, SqliteStore deposits, SqliteStore withdrawals) {
        return StatusReport.render(l1(cfg, null), l2(cfg, null), deposits, withdrawals);
    }

    /** A tiny plain-text status page (a recruiter can curl it or open it in a browser). */
    private static void serveStatus(Config cfg, SqliteStore deposits, SqliteStore withdrawals) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", cfg.statusPort()), 0);
        server.createContext("/", exchange -> {
            byte[] body;
            try {
                body = status(cfg, deposits, withdrawals).getBytes(StandardCharsets.UTF_8);
            } catch (RuntimeException e) {
                body = ("status unavailable: " + e.getMessage()).getBytes(StandardCharsets.UTF_8);
            }
            exchange.getResponseHeaders().add("content-type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        System.out.println("status page on http://127.0.0.1:" + cfg.statusPort() + "/");
    }

    private static void usage() {
        System.out.println("""
                usage: rollup-bridge <command> [--once]
                  deposit-relayer | proposer | withdrawal-relayer | challenger
                  guardian [watch | resolve <outputIndex> | pause | unpause]
                  all        run every service in one JVM (demo)
                  demo-attack fake-leaf [amountWei]   DEMO ONLY: post a lying output and prove a fake withdrawal
                  status     print the current state
                configuration comes from environment variables, see .env.example""");
    }
}
