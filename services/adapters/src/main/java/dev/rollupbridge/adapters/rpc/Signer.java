package dev.rollupbridge.adapters.rpc;

import dev.rollupbridge.core.Hex;
import java.math.BigInteger;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.utils.Numeric;

/**
 * secp256k1 transaction signing (EIP-155 legacy transactions) via web3j-crypto. The private key lives only in
 * memory; it is read from the environment by the app and is a throw-away test key in every demo.
 */
public final class Signer {
    private final Credentials credentials;

    public Signer(String privateKeyHex) {
        this.credentials = Credentials.create(privateKeyHex);
    }

    /** Lower-case 0x address of this key. */
    public String address() {
        return Hex.address(credentials.getAddress());
    }

    /** Signs a legacy transaction. {@code to} may be null for a contract creation. */
    public String sign(
            long chainId, BigInteger nonce, BigInteger gasPrice, BigInteger gasLimit, String to, BigInteger value, byte[] data) {
        RawTransaction tx = RawTransaction.createTransaction(
                nonce, gasPrice, gasLimit, to == null ? "" : to, value, Numeric.toHexString(data));
        return Numeric.toHexString(TransactionEncoder.signMessage(tx, chainId, credentials));
    }
}
