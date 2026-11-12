// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {L1Bridge} from "../../src/l1/L1Bridge.sol";
import {Withdrawal} from "../../src/lib/Withdrawals.sol";

/// @notice Withdrawal target that rejects ETH.
contract RevertingTarget {
    receive() external payable {
        revert("no thanks");
    }
}

/// @notice Withdrawal target that returns a huge payload (return bomb) and accepts the ETH.
contract ReturnBombTarget {
    receive() external payable {
        assembly {
            return(0, 50000)
        }
    }
}

/// @notice Target that tries to re-enter `finalizeWithdrawal` and `claim` while receiving ETH, and swallows
///         the failure so the outer payout still succeeds.
contract ReentrantTarget {
    L1Bridge public immutable BRIDGE;
    Withdrawal public again;
    bool public reenterFinalize;
    bool public reenterClaim;
    uint256 public blockedReentries;
    bool public rejecting;
    uint256 public receives;

    constructor(L1Bridge bridge) {
        BRIDGE = bridge;
    }

    function arm(Withdrawal calldata w, bool finalize_, bool claim_) external {
        again = w;
        reenterFinalize = finalize_;
        reenterClaim = claim_;
    }

    function setRejecting(bool r) external {
        rejecting = r;
    }

    function callClaim() external {
        BRIDGE.claim();
    }

    receive() external payable {
        require(!rejecting, "rejecting");
        ++receives;
        if (reenterFinalize) {
            try BRIDGE.finalizeWithdrawal(again) {}
            catch {
                ++blockedReentries;
            }
        }
        if (reenterClaim) {
            try BRIDGE.claim() {}
            catch {
                ++blockedReentries;
            }
        }
    }
}

/// @notice Refuses ETH until `open` is set: lets a test credit the pull-payment balance and claim later.
contract ToggleTarget {
    bool public open;

    function setOpen(bool open_) external {
        open = open_;
    }

    function callClaim(L1Bridge bridge) external {
        bridge.claim();
    }

    receive() external payable {
        require(open, "closed");
    }
}
