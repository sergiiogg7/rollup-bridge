package dev.rollupbridge.testkit;

import dev.rollupbridge.adapters.rpc.JsonRpcClient;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

/** A native {@code anvil} child process (no Docker). Closing it kills the process. */
public final class Anvil implements AutoCloseable {
    private final Process process;
    private final int port;
    private final long chainId;
    private final JsonRpcClient rpc;

    private Anvil(Process process, int port, long chainId) {
        this.process = process;
        this.port = port;
        this.chainId = chainId;
        this.rpc = new JsonRpcClient(url());
    }

    /** True if an {@code anvil} binary can be started from the PATH. */
    public static boolean isAvailable() {
        try {
            Process p = new ProcessBuilder("anvil", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Starts anvil on {@code port} (0 = any free port) with automine and full state history. */
    public static Anvil start(int port, long chainId) {
        try {
            int p = port == 0 ? freePort() : port;
            Process process = new ProcessBuilder(
                            "anvil", "--port", String.valueOf(p), "--chain-id", String.valueOf(chainId), "--silent")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Anvil anvil = new Anvil(process, p, chainId);
            Runtime.getRuntime().addShutdownHook(new Thread(anvil::close));
            anvil.awaitReady();
            return anvil;
        } catch (IOException e) {
            throw new IllegalStateException("cannot start anvil: " + e.getMessage(), e);
        }
    }

    private void awaitReady() {
        for (int i = 0; i < 100; i++) {
            try {
                rpc.call("eth_chainId");
                return;
            } catch (RuntimeException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        close();
        throw new IllegalStateException("anvil on port " + port + " did not become ready");
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    public String url() {
        return "http://127.0.0.1:" + port;
    }

    public long chainId() {
        return chainId;
    }

    public JsonRpcClient rpc() {
        return rpc;
    }

    /** Mines {@code n} empty blocks. */
    public void mine(long n) {
        rpc.call("anvil_mine", "0x" + Long.toHexString(n));
    }

    /** Moves chain time forward and mines a block so that the new time is visible to the next transaction. */
    public void increaseTime(long seconds) {
        rpc.call("evm_increaseTime", seconds);
        mine(1);
    }

    /** {@code anvil_reorg}: replaces the last {@code depth} blocks by empty blocks (their transactions are dropped). */
    public void reorg(long depth) {
        rpc.call("anvil_reorg", depth, java.util.List.of());
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }
}
