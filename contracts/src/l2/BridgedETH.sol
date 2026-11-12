// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {ERC20} from "@openzeppelin/contracts/token/ERC20/ERC20.sol";

/// @title BridgedETH
/// @notice The L2 representation of ETH locked in the L1 bridge: a plain ERC-20 that only the `L2Bridge`
///         can mint (when a deposit is relayed) and burn (when a withdrawal starts).
contract BridgedETH is ERC20 {
    /// @notice The only account allowed to mint and burn.
    address public immutable BRIDGE;

    error NotBridge();

    modifier onlyBridge() {
        if (msg.sender != BRIDGE) revert NotBridge();
        _;
    }

    // Slither "missing-zero-check": the only deployer is L2Bridge, which passes address(this), never zero.
    // slither-disable-start missing-zero-check
    constructor(address bridge_) ERC20("Bridged ETH", "bETH") {
        BRIDGE = bridge_;
    }
    // slither-disable-end missing-zero-check

    function mint(address to, uint256 amount) external onlyBridge {
        _mint(to, amount);
    }

    /// @dev Burns from `from` without allowance: the bridge only calls this with `from == msg.sender`
    ///      of `L2Bridge.initiateWithdrawal`, i.e. the holder asked for it.
    function burn(address from, uint256 amount) external onlyBridge {
        _burn(from, amount);
    }
}
