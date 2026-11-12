package dev.rollupbridge.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeccakAndAbiTest {

    @Test
    void keccakOfEmptyInputIsTheEthereumConstant() {
        assertThat(Hex.encode(Keccak256.hash(new byte[0])))
                .isEqualTo("0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470");
    }

    @Test
    void keccakOfAbc() {
        assertThat(Hex.encode(Keccak256.hash("abc")))
                .isEqualTo("0x4e03657aea45a94fc7d47ba826c8d667c0d1e6e33a64a036ec44f58fa12d6c45");
    }

    @Test
    void keccakHandlesInputsAroundTheRateBoundary() {
        // expected values produced by `cast keccak`
        byte[] a3 = new byte[200];
        java.util.Arrays.fill(a3, (byte) 0xa3);
        assertThat(Hex.encode(Keccak256.hash(a3)))
                .isEqualTo("0x3a57666b048777f2c953dc4456f45a2588e1cb6f2da760122d530ac2ce607d4a");
        assertThat(Hex.encode(Keccak256.hash(new byte[136])))
                .isEqualTo("0x3a5912a7c5faa06ee4fe906253e339467a9ce87d533c65be3c15cb231cdb25f9");
        assertThat(Hex.encode(Keccak256.hash(new byte[136], new byte[1])))
                .isEqualTo("0xbee7fbb405cb0d91a8775e338c4a5e4b5d6b2d051f687fa942043cffdc73bd28");
    }

    @Test
    void functionSelectorsMatchWellKnownValues() {
        assertThat(Hex.encode(Abi.selector("transfer(address,uint256)"))).isEqualTo("0xa9059cbb");
        assertThat(Hex.encode(Abi.selector("balanceOf(address)"))).isEqualTo("0x70a08231");
    }

    @Test
    void encodesStaticArgumentsAsWords() {
        byte[] data = Abi.encodeCall(
                "transfer(address,uint256)",
                Abi.address("0x70997970C51812dc3A010C7d01b50e0d17dc79C8"),
                Abi.uint(BigInteger.valueOf(1000)));
        assertThat(Hex.encode(data))
                .isEqualTo("0xa9059cbb"
                        + "00000000000000000000000070997970c51812dc3a010c7d01b50e0d17dc79c8"
                        + "00000000000000000000000000000000000000000000000000000000000003e8");
    }

    @Test
    void encodesDynamicArrayWithOffsetAndLength() {
        Bytes32 x = Bytes32.of("0x" + "11".repeat(32));
        byte[] enc = Abi.encode(List.of(Abi.uint(7), new Abi.B32Array(List.of(x))));
        Abi.Words w = new Abi.Words(enc);
        assertThat(w.count()).isEqualTo(4);
        assertThat(w.uintAsLong(0)).isEqualTo(7);
        assertThat(w.uintAsLong(1)).isEqualTo(64); // offset of the array tail: after two head words
        assertThat(w.uintAsLong(2)).isEqualTo(1); // length
        assertThat(w.bytes32(3)).isEqualTo(x);
    }

    @Test
    void staticTupleIsInlinedInTheHead() {
        Withdrawal w = new Withdrawal(3, "0x" + "aa".repeat(20), "0x" + "bb".repeat(20), BigInteger.TEN);
        byte[] enc = Abi.encode(List.of(w.asTuple(), Abi.uint(9)));
        Abi.Words words = new Abi.Words(enc);
        assertThat(words.count()).isEqualTo(5);
        assertThat(words.uintAsLong(0)).isEqualTo(3);
        assertThat(words.address(1)).isEqualTo("0x" + "aa".repeat(20));
        assertThat(words.uint(3)).isEqualTo(BigInteger.TEN);
        assertThat(words.uintAsLong(4)).isEqualTo(9);
    }

    @Test
    void leafIsHashOfFourWords() {
        Withdrawal w = new Withdrawal(0, "0x" + "00".repeat(19) + "01", "0x" + "00".repeat(19) + "02", BigInteger.ONE);
        byte[] preimage = Abi.encode(w.asTuple().members());
        assertThat(preimage).hasSize(128);
        assertThat(w.leaf().toBytes()).isEqualTo(Keccak256.hash(preimage));
    }

    @Test
    void addressRejectsGarbage() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Hex.address("0x1234"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
