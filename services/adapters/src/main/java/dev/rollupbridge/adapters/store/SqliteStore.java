package dev.rollupbridge.adapters.store;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Withdrawal;
import dev.rollupbridge.core.port.BridgeStore;
import dev.rollupbridge.core.state.DepositRecord;
import dev.rollupbridge.core.state.DepositState;
import dev.rollupbridge.core.state.WithdrawalRecord;
import dev.rollupbridge.core.state.WithdrawalState;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * SQLite implementation of {@link BridgeStore} over plain JDBC. Every save is its own auto-committed
 * transaction, so after {@code kill -9} the store holds exactly the steps that completed (the services are
 * written so that "state saved, action not done" and "action done, state not saved" are both safe to resume).
 */
public final class SqliteStore implements BridgeStore, AutoCloseable {
    private final Connection conn;

    /** @param jdbcUrl e.g. {@code jdbc:sqlite:/path/to/relayer.db} or {@code jdbc:sqlite::memory:} */
    public SqliteStore(String jdbcUrl) {
        try {
            conn = DriverManager.getConnection(jdbcUrl);
            try (Statement s = conn.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=FULL");
                s.execute("CREATE TABLE IF NOT EXISTS deposits ("
                        + "id INTEGER PRIMARY KEY, to_addr TEXT NOT NULL, amount TEXT NOT NULL,"
                        + " l1_block INTEGER NOT NULL, l1_hash TEXT NOT NULL, state TEXT NOT NULL, l2_tx TEXT)");
                s.execute("CREATE TABLE IF NOT EXISTS withdrawals ("
                        + "id INTEGER PRIMARY KEY, sender TEXT NOT NULL, target TEXT NOT NULL, amount TEXT NOT NULL,"
                        + " l2_block INTEGER NOT NULL, state TEXT NOT NULL, output_index INTEGER, tx TEXT, paid INTEGER)");
                s.execute("CREATE TABLE IF NOT EXISTS cursors (name TEXT PRIMARY KEY, value INTEGER NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS checked ("
                        + "output_index INTEGER NOT NULL, root TEXT NOT NULL, PRIMARY KEY (output_index, root))");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open store " + jdbcUrl, e);
        }
    }

    public static SqliteStore file(java.nio.file.Path path) {
        return new SqliteStore("jdbc:sqlite:" + path.toAbsolutePath());
    }

    // ----- deposits -----

    @Override
    public synchronized Optional<DepositRecord> deposit(long id) {
        return queryDeposits("SELECT * FROM deposits WHERE id = ?", id).stream().findFirst();
    }

    @Override
    public synchronized void saveDeposit(DepositRecord r) {
        String l2Tx = r.state() instanceof DepositState.Done d ? d.l2TxHash() : null;
        exec("INSERT INTO deposits (id, to_addr, amount, l1_block, l1_hash, state, l2_tx) VALUES (?,?,?,?,?,?,?)"
                + " ON CONFLICT(id) DO UPDATE SET to_addr=excluded.to_addr, amount=excluded.amount,"
                + " l1_block=excluded.l1_block, l1_hash=excluded.l1_hash, state=excluded.state, l2_tx=excluded.l2_tx",
                r.id(), r.to(), r.amount().toString(), r.l1Block(), r.l1BlockHash().hex(), r.state().name(), l2Tx);
    }

    @Override
    public synchronized List<DepositRecord> activeDeposits() {
        return queryDeposits("SELECT * FROM deposits WHERE state NOT IN ('Done','Dropped') ORDER BY id");
    }

    @Override
    public synchronized List<DepositRecord> allDeposits() {
        return queryDeposits("SELECT * FROM deposits ORDER BY id");
    }

    private List<DepositRecord> queryDeposits(String sql, Object... args) {
        List<DepositRecord> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new DepositRecord(
                            rs.getLong("id"),
                            rs.getString("to_addr"),
                            new BigInteger(rs.getString("amount")),
                            rs.getLong("l1_block"),
                            Bytes32.of(rs.getString("l1_hash")),
                            depositState(rs.getString("state"), rs.getString("l2_tx"))));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    private static DepositState depositState(String name, String l2Tx) {
        return switch (name) {
            case "Seen" -> new DepositState.Seen();
            case "Confirmed" -> new DepositState.Confirmed();
            case "Relaying" -> new DepositState.Relaying();
            case "Done" -> new DepositState.Done(l2Tx);
            case "Dropped" -> new DepositState.Dropped();
            default -> throw new IllegalStateException("unknown deposit state " + name);
        };
    }

    // ----- withdrawals -----

    @Override
    public synchronized Optional<WithdrawalRecord> withdrawal(long id) {
        return queryWithdrawals("SELECT * FROM withdrawals WHERE id = ?", id).stream().findFirst();
    }

    @Override
    public synchronized void saveWithdrawal(WithdrawalRecord r) {
        Withdrawal w = r.withdrawal();
        Long outputIndex = null;
        String tx = null;
        Integer paid = null;
        switch (r.state()) {
            case WithdrawalState.Initiated i -> { }
            case WithdrawalState.Included i -> outputIndex = i.outputIndex();
            case WithdrawalState.Proven p -> {
                outputIndex = p.outputIndex();
                tx = p.proveTx();
            }
            case WithdrawalState.Finalized f -> {
                tx = f.txHash();
                paid = f.paid() ? 1 : 0;
            }
            case WithdrawalState.Invalidated i -> outputIndex = i.deletedOutputIndex();
        }
        exec("INSERT INTO withdrawals (id, sender, target, amount, l2_block, state, output_index, tx, paid)"
                + " VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET state=excluded.state,"
                + " output_index=excluded.output_index, tx=excluded.tx, paid=excluded.paid, l2_block=excluded.l2_block",
                w.id(), w.sender(), w.target(), w.amount().toString(), r.l2Block(), r.state().name(), outputIndex, tx, paid);
    }

    @Override
    public synchronized List<WithdrawalRecord> activeWithdrawals() {
        return queryWithdrawals("SELECT * FROM withdrawals WHERE state <> 'Finalized' ORDER BY id");
    }

    @Override
    public synchronized List<WithdrawalRecord> allWithdrawals() {
        return queryWithdrawals("SELECT * FROM withdrawals ORDER BY id");
    }

    private List<WithdrawalRecord> queryWithdrawals(String sql, Object... args) {
        List<WithdrawalRecord> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Withdrawal w = new Withdrawal(
                            rs.getLong("id"), rs.getString("sender"), rs.getString("target"),
                            new BigInteger(rs.getString("amount")));
                    long outputIndex = rs.getLong("output_index");
                    String tx = rs.getString("tx");
                    WithdrawalState state = switch (rs.getString("state")) {
                        case "Initiated" -> new WithdrawalState.Initiated();
                        case "Included" -> new WithdrawalState.Included(outputIndex);
                        case "Proven" -> new WithdrawalState.Proven(outputIndex, tx);
                        case "Finalized" -> new WithdrawalState.Finalized(tx, rs.getInt("paid") == 1);
                        case "Invalidated" -> new WithdrawalState.Invalidated(outputIndex);
                        default -> throw new IllegalStateException("unknown withdrawal state");
                    };
                    out.add(new WithdrawalRecord(w, rs.getLong("l2_block"), state));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    // ----- cursors and challenger bookkeeping -----

    @Override
    public synchronized OptionalLong cursor(String name) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM cursors WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void setCursor(String name, long value) {
        exec("INSERT INTO cursors (name, value) VALUES (?, ?) ON CONFLICT(name) DO UPDATE SET value=excluded.value", name, value);
    }

    @Override
    public synchronized boolean isChecked(long outputIndex, Bytes32 root) {
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT 1 FROM checked WHERE output_index = ? AND root = ?")) {
            ps.setLong(1, outputIndex);
            ps.setString(2, root.hex());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void markChecked(long outputIndex, Bytes32 root) {
        exec("INSERT OR IGNORE INTO checked (output_index, root) VALUES (?, ?)", outputIndex, root.hex());
    }

    // ----- plumbing -----

    private void exec(String sql, Object... args) {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            bind(ps, args);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void bind(PreparedStatement ps, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            ps.setObject(i + 1, args[i]);
        }
    }

    @Override
    public synchronized void close() {
        try {
            conn.close();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
