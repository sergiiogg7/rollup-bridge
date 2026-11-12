package dev.rollupbridge.adapters.rpc;

import dev.rollupbridge.core.Abi;
import dev.rollupbridge.core.Hex;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Turns revert data (a 4-byte custom error selector) into the error name, for readable logs. */
public final class ErrorNames {
    private static final Map<String, String> BY_SELECTOR = new HashMap<>();

    static {
        List<String> signatures = List.of(
                // OutputOracle
                "NotProposer()", "NotGuardian()", "ZeroAddress()", "ZeroParameter()", "WrongBond(uint256,uint256)",
                "OutputNotFound(uint256)", "NotMonotonic()", "WrongStatus(uint8)", "WindowClosed()",
                "ResolutionExpired()", "ResolutionStillOpen()", "OutputAlreadyFinal()", "OutputNotFinalizable()",
                "NothingToWithdraw()", "TransferFailed()",
                // L1Bridge
                "IsPaused()", "ZeroAmount()", "DepositTooLarge(uint256,uint256)", "TotalLockedExceeded()",
                "BadProofLength()", "OutputNotProvable(uint8)", "IdOutOfRange(uint256,uint64)", "InvalidProof()",
                "AlreadyProven()", "AlreadyFinalized()", "NotProven()", "InsufficientLocked()", "NothingToClaim()",
                // L2Bridge
                "NotRelayer()", "DepositAlreadyProcessed(uint256)",
                // OpenZeppelin
                "OwnableUnauthorizedAccount(address)", "ReentrancyGuardReentrantCall()");
        for (String s : signatures) {
            BY_SELECTOR.put(Hex.encode(Abi.selector(s)), s);
        }
    }

    private ErrorNames() {}

    public static String describe(String revertData, String fallback) {
        if (revertData == null || revertData.length() < 10) {
            return fallback;
        }
        String name = BY_SELECTOR.get(revertData.substring(0, 10).toLowerCase(java.util.Locale.ROOT));
        return name != null ? name : fallback + " [" + revertData + "]";
    }
}
