package dev.rollupbridge.app;

import com.sun.net.httpserver.HttpServer;
import dev.rollupbridge.adapters.evm.EvmL1Gateway;
import dev.rollupbridge.adapters.evm.EvmL2Gateway;
import dev.rollupbridge.adapters.rpc.JsonRpcClient;
import dev.rollupbridge.adapters.store.SqliteStore;
import dev.rollupbridge.core.metrics.MetricsRegistry;
import dev.rollupbridge.core.port.L1Reader;
import dev.rollupbridge.core.port.L1Writer;
import dev.rollupbridge.core.port.L2Reader;
import dev.rollupbridge.core.port.L2Writer;
import dev.rollupbridge.services.BridgeCollector;
import dev.rollupbridge.services.Challenger;
import dev.rollupbridge.services.DepositRelayer;
import dev.rollupbridge.services.Guardian;
import dev.rollupbridge.services.Metered;
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
import java.util.function.Supplier;

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
            case "deposit-relayer" -> {
                SqliteStore deposits = depositStore(cfg);
                run(List.of(depositRelayer(cfg, deposits)), cfg, once, deposits, null, null);
            }
            case "proposer" -> run(List.of(proposer(cfg)), cfg, once, null, null, null);
            case "withdrawal-relayer" -> {
                SqliteStore withdrawals = withdrawalStore(cfg);
                run(List.of(withdrawalRelayer(cfg, withdrawals)), cfg, once, null, withdrawals, null);
            }
            case "challenger" -> {
                SqliteStore checked = challengerStore(cfg);
                run(List.of(challenger(cfg, checked)), cfg, once, null, null, checked);
            }
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

    private static SqliteStore challengerStore(Config c) {
        return new SqliteStore("jdbc:sqlite:" + c.dbDir().resolve("challenger.db").toAbsolutePath());
    }

    private static SqliteStore withdrawalStore(Config c) {
        return new SqliteStore("jdbc:sqlite:" + c.dbDir().resolve("withdrawal-relayer.db").toAbsolutePath());
    }

    /** One registry per process; every port of every service is decorated with {@link Metered} (see {@code /metrics}). */
    private static final MetricsRegistry METRICS = new MetricsRegistry();

    private static L1Reader l1r(Config c, String service) {
        return Metered.wrap(L1Reader.class, l1(c, null), METRICS, service);
    }

    private static L1Writer l1w(Config c, String key, String service) {
        return Metered.wrap(L1Writer.class, l1(c, key), METRICS, service);
    }

    private static L2Reader l2r(Config c, String service) {
        return Metered.wrap(L2Reader.class, l2(c, null), METRICS, service);
    }

    private static Service depositRelayer(Config c, SqliteStore store) {
        String s = "deposit-relayer";
        return new DepositRelayer(
                l1r(c, s), l2r(c, s), Metered.wrap(L2Writer.class, l2(c, c.require(c.relayerKey(), "RELAYER_KEY")), METRICS, s), store,
                new DepositRelayer.Config(c.depositConfirmations(), c.l1StartBlock(), Math.max(20, c.depositConfirmations() + 5)),
                METRICS);
    }

    private static Service proposer(Config c) {
        String s = "proposer";
        return new Proposer(l1r(c, s), l1w(c, c.require(c.proposerKey(), "PROPOSER_KEY"), s), l2r(c, s), new Proposer.Config(0));
    }

    private static Service withdrawalRelayer(Config c, SqliteStore store) {
        String s = "withdrawal-relayer";
        return new WithdrawalRelayer(l1r(c, s), l1w(c, c.require(c.relayerKey(), "RELAYER_KEY"), s), l2r(c, s), store);
    }

    private static Service challenger(Config c, SqliteStore store) {
        String s = "challenger";
        return new Challenger(l1r(c, s), l1w(c, c.require(c.challengerKey(), "CHALLENGER_KEY"), s), new OutputVerifier(l2r(c, s)), store);
    }

    private static Guardian guardianService(Config c) {
        String s = "guardian";
        return new Guardian(l1r(c, s), l1w(c, c.require(c.guardianKey(), "GUARDIAN_KEY"), s), new OutputVerifier(l2r(c, s)));
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
            case "watch" -> run(List.of(g), cfg, List.of(args).contains("--once"), null, null, null);
            case "resolve" -> System.out.println("proposer was wrong: " + g.resolve(Long.parseLong(args[2])));
            case "pause" -> g.pause();
            case "unpause" -> g.unpause();
            default -> usage();
        }
    }

    private static void all(Config cfg, boolean once) throws IOException {
        SqliteStore deposits = depositStore(cfg);
        SqliteStore withdrawals = withdrawalStore(cfg);
        SqliteStore challengerDb = challengerStore(cfg);
        List<Service> services = List.of(
                depositRelayer(cfg, deposits),
                proposer(cfg),
                withdrawalRelayer(cfg, withdrawals),
                challenger(cfg, challengerDb),
                guardianService(cfg));
        run(services, cfg, once, deposits, withdrawals, challengerDb, () -> status(cfg, deposits, withdrawals));
    }

    private static void run(List<Service> services, Config cfg, boolean once, SqliteStore deposits, SqliteStore withdrawals, SqliteStore checked) {
        run(services, cfg, once, deposits, withdrawals, checked, null);
    }

    private static void run(
            List<Service> services, Config cfg, boolean once,
            SqliteStore deposits, SqliteStore withdrawals, SqliteStore checked, Supplier<String> statusPage) {
        if (once) {
            services.forEach(Service::tick);
            return;
        }
        if (cfg.statusPort() > 0) {
            try {
                serveHttp(cfg, statusPage, new BridgeCollector(l1r(cfg, "collector"), l2r(cfg, "collector"), deposits, withdrawals, checked, METRICS));
            } catch (IOException e) {
                throw new IllegalStateException("cannot listen on STATUS_PORT " + cfg.statusPort(), e);
            }
        }
        List<Thread> threads = new ArrayList<>();
        for (Service s : services) {
            Thread t = new Thread(new ServiceRunner(s, Duration.ofMillis(s instanceof Challenger ? cfg.challengerPollMillis() : cfg.pollMillis()), METRICS), s.name());
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

    /**
     * Listens on {@code STATUS_PORT} (127.0.0.1 only): {@code /metrics} in the Prometheus text format (the gauges are
     * refreshed right before each scrape) and, when a supplier is given, a plain-text status page on {@code /}.
     */
    private static void serveHttp(Config cfg, Supplier<String> statusPage, BridgeCollector collector) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", cfg.statusPort()), 0);
        server.createContext("/metrics", exchange -> {
            collector.collect();
            reply(exchange, METRICS.render(), "text/plain; version=0.0.4; charset=utf-8");
        });
        if (statusPage != null) {
            server.createContext("/", exchange -> {
                String body;
                try {
                    body = statusPage.get();
                } catch (RuntimeException e) {
                    body = "status unavailable: " + e.getMessage();
                }
                reply(exchange, body, "text/plain; charset=utf-8");
            });
        }
        server.start();
        System.out.println("metrics on http://127.0.0.1:" + cfg.statusPort() + "/metrics" + (statusPage != null ? ", status page on /" : ""));
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, String text, String contentType) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
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
