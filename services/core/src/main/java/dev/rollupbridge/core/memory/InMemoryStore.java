package dev.rollupbridge.core.memory;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.port.BridgeStore;
import dev.rollupbridge.core.state.DepositRecord;
import dev.rollupbridge.core.state.DepositState;
import dev.rollupbridge.core.state.WithdrawalRecord;
import dev.rollupbridge.core.state.WithdrawalState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

/** Volatile {@link BridgeStore} for unit tests and throw-away runs. */
public final class InMemoryStore implements BridgeStore {
    private final Map<Long, DepositRecord> deposits = new TreeMap<>();
    private final Map<Long, WithdrawalRecord> withdrawals = new TreeMap<>();
    private final Map<String, Long> cursors = new TreeMap<>();
    private final Set<String> checked = new HashSet<>();

    @Override
    public synchronized Optional<DepositRecord> deposit(long id) {
        return Optional.ofNullable(deposits.get(id));
    }

    @Override
    public synchronized void saveDeposit(DepositRecord record) {
        deposits.put(record.id(), record);
    }

    @Override
    public synchronized List<DepositRecord> activeDeposits() {
        return deposits.values().stream()
                .filter(d -> !(d.state() instanceof DepositState.Done) && !(d.state() instanceof DepositState.Dropped))
                .toList();
    }

    @Override
    public synchronized List<DepositRecord> allDeposits() {
        return new ArrayList<>(deposits.values());
    }

    @Override
    public synchronized Optional<WithdrawalRecord> withdrawal(long id) {
        return Optional.ofNullable(withdrawals.get(id));
    }

    @Override
    public synchronized void saveWithdrawal(WithdrawalRecord record) {
        withdrawals.put(record.withdrawal().id(), record);
    }

    @Override
    public synchronized List<WithdrawalRecord> activeWithdrawals() {
        return withdrawals.values().stream()
                .filter(w -> !(w.state() instanceof WithdrawalState.Finalized))
                .toList();
    }

    @Override
    public synchronized List<WithdrawalRecord> allWithdrawals() {
        return new ArrayList<>(withdrawals.values());
    }

    @Override
    public synchronized OptionalLong cursor(String name) {
        Long v = cursors.get(name);
        return v == null ? OptionalLong.empty() : OptionalLong.of(v);
    }

    @Override
    public synchronized void setCursor(String name, long value) {
        cursors.put(name, value);
    }

    @Override
    public synchronized boolean isChecked(long outputIndex, Bytes32 root) {
        return checked.contains(outputIndex + ":" + root.hex());
    }

    @Override
    public synchronized void markChecked(long outputIndex, Bytes32 root) {
        checked.add(outputIndex + ":" + root.hex());
    }
}
