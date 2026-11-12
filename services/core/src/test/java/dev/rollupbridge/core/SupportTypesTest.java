package dev.rollupbridge.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.rollupbridge.core.memory.InMemoryStore;
import dev.rollupbridge.core.model.OutputStatus;
import dev.rollupbridge.core.model.ProofInfo;
import dev.rollupbridge.core.port.GatewayException;
import dev.rollupbridge.core.state.DepositRecord;
import dev.rollupbridge.core.state.DepositState;
import dev.rollupbridge.core.state.WithdrawalRecord;
import dev.rollupbridge.core.state.WithdrawalState;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupportTypesTest {
    private static final String ADDR_A = "0x" + "aa".repeat(20);
    private static final String ADDR_B = "0x" + "bb".repeat(20);

    @Test
    void hexRoundTripsAndParsesQuantities() {
        byte[] data = {0, 1, (byte) 0xab, (byte) 0xff};
        assertThat(Hex.encode(data)).isEqualTo("0x0001abff");
        assertThat(Hex.decode("0x0001ABFF")).isEqualTo(data);
        assertThat(Hex.decode("abc")).isEqualTo(new byte[] {0x0a, (byte) 0xbc}); // odd length is left-padded
        assertThat(Hex.quantityToLong("0x1f")).isEqualTo(31);
        assertThat(Hex.quantityToLong("0x")).isZero();
        assertThat(Hex.quantityToBigInteger("0x10")).isEqualTo(BigInteger.valueOf(16));
        assertThat(Hex.toQuantity(255L)).isEqualTo("0xff");
        assertThat(Hex.toQuantity(BigInteger.TEN)).isEqualTo("0xa");
        assertThatThrownBy(() -> Hex.decode("0xzz")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Hex.address("0x" + "AB".repeat(20))).isEqualTo("0x" + "ab".repeat(20));
    }

    @Test
    void bytes32HasValueSemantics() {
        Bytes32 a = Bytes32.of("0x" + "01".repeat(32));
        Bytes32 b = Bytes32.of(a.toBytes());
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).hasToString(a.hex());
        assertThat(a.compareTo(Bytes32.ZERO)).isPositive();
        assertThat(Bytes32.ZERO.compareTo(a)).isNegative();
        assertThat(a).isNotEqualTo("x");
        assertThatThrownBy(() -> Bytes32.of(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        byte[] copy = a.toBytes();
        copy[0] = 9;
        assertThat(a.toBytes()[0]).as("defensive copy").isEqualTo((byte) 1);
    }

    @Test
    void abiEncodesBoolAndBytes32AndRejectsOutOfRange() {
        byte[] enc = Abi.encode(List.of(new Abi.Bool(true), Abi.bytes32(Bytes32.ZERO), Abi.uint(BigInteger.ZERO)));
        Abi.Words w = new Abi.Words(enc);
        assertThat(w.bool(0)).isTrue();
        assertThat(w.bytes32(1)).isEqualTo(Bytes32.ZERO);
        assertThat(w.bool(2)).isFalse();
        assertThatThrownBy(() -> w.uint(3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Abi.Uint(BigInteger.valueOf(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Abi.Uint(BigInteger.ONE.shiftLeft(256))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Abi.topicToAddress("0x" + "00".repeat(12) + "cc".repeat(20))).isEqualTo("0x" + "cc".repeat(20));
        assertThat(Abi.bytes32List(List.of("0x" + "00".repeat(32)))).containsExactly(Bytes32.ZERO);
        assertThat(new Abi.Tuple(List.of(Abi.uint(1))).isDynamic()).isFalse();
        assertThat(new Abi.B32Array(List.of()).isDynamic()).isTrue();
        assertThat(new Abi.Tuple(List.of(new Abi.B32Array(List.of()))).isDynamic()).isTrue();
        assertThat(Abi.Words.of("0x" + "00".repeat(31) + "07").uintAsLong(0)).isEqualTo(7);
    }

    @Test
    void gatewayExceptionCarriesRevertData() {
        assertThat(new GatewayException("boom").revertData()).isNull();
        assertThat(new GatewayException("boom", new RuntimeException("x")).getCause()).hasMessage("x");
        assertThat(new GatewayException("boom", "0x1234abcd", null).revertData()).isEqualTo("0x1234abcd");
    }

    @Test
    void simpleModelTypes() {
        assertThat(ProofInfo.NONE.exists()).isFalse();
        assertThat(OutputStatus.valueOf("DELETED").ordinal()).isEqualTo(2);
    }

    @Test
    void inMemoryStoreBehavesLikeTheSqliteOne() {
        InMemoryStore store = new InMemoryStore();
        DepositRecord d0 = new DepositRecord(0, ADDR_A, BigInteger.ONE, 5, Bytes32.ZERO, new DepositState.Seen());
        DepositRecord d1 = new DepositRecord(1, ADDR_B, BigInteger.TWO, 6, Bytes32.ZERO, new DepositState.Done("0x1"));
        store.saveDeposit(d0);
        store.saveDeposit(d1);
        assertThat(store.deposit(0)).contains(d0);
        assertThat(store.deposit(9)).isEmpty();
        assertThat(store.activeDeposits()).containsExactly(d0);
        assertThat(store.allDeposits()).containsExactly(d0, d1);

        Withdrawal w = new Withdrawal(0, ADDR_A, ADDR_B, BigInteger.TEN);
        WithdrawalRecord r0 = new WithdrawalRecord(w, 3, new WithdrawalState.Initiated());
        store.saveWithdrawal(r0);
        assertThat(store.withdrawal(0)).contains(r0);
        assertThat(store.activeWithdrawals()).containsExactly(r0);
        store.saveWithdrawal(r0.transitionTo(new WithdrawalState.Finalized("0x2", true)));
        assertThat(store.activeWithdrawals()).isEmpty();
        assertThat(store.allWithdrawals()).hasSize(1);

        assertThat(store.cursor("c")).isEmpty();
        store.setCursor("c", 42);
        assertThat(store.cursor("c")).hasValue(42);

        assertThat(store.isChecked(1, Bytes32.ZERO)).isFalse();
        store.markChecked(1, Bytes32.ZERO);
        assertThat(store.isChecked(1, Bytes32.ZERO)).isTrue();
        assertThat(store.isChecked(2, Bytes32.ZERO)).isFalse();
    }
}
