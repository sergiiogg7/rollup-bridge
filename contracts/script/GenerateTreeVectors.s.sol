// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Script} from "forge-std/Script.sol";
import {IncrementalMerkleTree} from "../src/lib/IncrementalMerkleTree.sol";
import {Withdrawal, WithdrawalHash} from "../src/lib/Withdrawals.sol";
import {TreeBuilder} from "../test/utils/TreeBuilder.sol";

/// @dev Holds an `IncrementalMerkleTree` in storage so the script can read the real library's root.
contract VectorTree {
    using IncrementalMerkleTree for IncrementalMerkleTree.Tree;

    IncrementalMerkleTree.Tree internal tree;

    function insert(bytes32 leaf) external {
        tree.insert(leaf);
    }

    function root() external view returns (bytes32) {
        return tree.root();
    }
}

/// @notice Writes `testdata/tree-vectors.json`, the shared test vectors for the Solidity and Java trees:
///         the withdrawals, their leaf hashes, the root of the tree at every size 0..N (computed by the
///         real `IncrementalMerkleTree`), the zero hashes and Merkle proofs for selected sizes (computed by
///         the independent reference builder).
///         Run: `forge script script/GenerateTreeVectors.s.sol` (from `contracts/`).
contract GenerateTreeVectors is Script {
    uint256 internal constant N = 200;

    function run() external {
        VectorTree vt = new VectorTree();

        Withdrawal[] memory w = new Withdrawal[](N);
        bytes32[] memory leaves = new bytes32[](N);
        bytes32[] memory roots = new bytes32[](N + 1);
        roots[0] = vt.root();
        for (uint256 i = 0; i < N; ++i) {
            w[i] = Withdrawal({
                id: i,
                // forge-lint: disable-next-line(unsafe-typecast)
                sender: address(uint160(0x1000 + i)),
                // forge-lint: disable-next-line(unsafe-typecast)
                target: address(uint160(0x5000 + 7 * i)),
                amount: (i + 1) * 1e15
            });
            leaves[i] = WithdrawalHash.leaf(w[i]);
            vt.insert(leaves[i]);
            roots[i + 1] = vt.root();
        }

        string memory json = string.concat(
            "{\n",
            '  "depth": 20,\n',
            '  "zeroHashes": ',
            _array(_zeros()),
            ",\n",
            '  "withdrawals": ',
            _withdrawals(w),
            ",\n",
            '  "leaves": ',
            _array(leaves),
            ",\n",
            '  "roots": ',
            _array(roots),
            ",\n",
            '  "proofs": [',
            _proofs(leaves),
            "\n  ]\n}\n"
        );
        vm.writeFile("../testdata/tree-vectors.json", json);
    }

    function _zeros() internal pure returns (bytes32[] memory z) {
        z = new bytes32[](21);
        for (uint256 i = 0; i <= 20; ++i) {
            z[i] = IncrementalMerkleTree.zeroHash(i);
        }
    }

    function _array(bytes32[] memory a) internal pure returns (string memory s) {
        s = "[";
        for (uint256 i = 0; i < a.length; ++i) {
            s = string.concat(s, i == 0 ? "" : ",", '"', vm.toString(a[i]), '"');
        }
        s = string.concat(s, "]");
    }

    function _withdrawals(Withdrawal[] memory w) internal pure returns (string memory s) {
        s = "[";
        for (uint256 i = 0; i < w.length; ++i) {
            s = string.concat(
                s,
                i == 0 ? "" : ",",
                '{"id":',
                vm.toString(w[i].id),
                ',"sender":"',
                vm.toString(w[i].sender),
                '","target":"',
                vm.toString(w[i].target),
                '","amount":"',
                vm.toString(w[i].amount),
                '"}'
            );
        }
        s = string.concat(s, "]");
    }

    function _proofs(bytes32[] memory leaves) internal pure returns (string memory s) {
        uint256[6] memory fullSizes = [uint256(1), 2, 3, 5, 8, 33];
        bool first = true;
        for (uint256 k = 0; k < fullSizes.length; ++k) {
            for (uint256 i = 0; i < fullSizes[k]; ++i) {
                s = string.concat(s, first ? "\n    " : ",\n    ", _proofEntry(leaves, fullSizes[k], i));
                first = false;
            }
        }
        uint256[4] memory sampled = [uint256(0), 63, 127, 199];
        for (uint256 i = 0; i < sampled.length; ++i) {
            s = string.concat(s, ",\n    ", _proofEntry(leaves, N, sampled[i]));
        }
    }

    function _proofEntry(bytes32[] memory leaves, uint256 size, uint256 index) internal pure returns (string memory) {
        bytes32[] memory head = TreeBuilder.head(leaves, size);
        return string.concat(
            '{"size":',
            vm.toString(size),
            ',"index":',
            vm.toString(index),
            ',"proof":',
            _array(TreeBuilder.proof(head, index)),
            "}"
        );
    }
}
