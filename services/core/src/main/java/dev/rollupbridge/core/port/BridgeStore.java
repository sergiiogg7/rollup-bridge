package dev.rollupbridge.core.port;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.state.DepositRecord;
import dev.rollupbridge.core.state.WithdrawalRecord;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Persistence port (analogy: the repository layer). Services keep ALL their progress here, so a process that
 * is killed at any point resumes from the last committed step. Adapters: SQLite, in-memory (tests).
 */
public interface BridgeStore {
    Optional<DepositRecord> deposit(long id);

    void saveDeposit(DepositRecord record);

    /** Every deposit that is neither Done nor Dropped, lowest id first. */
    List<DepositRecord> activeDeposits();

    List<DepositRecord> allDeposits();

    Optional<WithdrawalRecord> withdrawal(long id);

    void saveWithdrawal(WithdrawalRecord record);

    /** Every withdrawal that is not Finalized, lowest id first. */
    List<WithdrawalRecord> activeWithdrawals();

    List<WithdrawalRecord> allWithdrawals();

    OptionalLong cursor(String name);

    void setCursor(String name, long value);

    /** Challenger bookkeeping: this exact (index, root) was already verified and found correct. */
    boolean isChecked(long outputIndex, Bytes32 root);

    void markChecked(long outputIndex, Bytes32 root);
}
