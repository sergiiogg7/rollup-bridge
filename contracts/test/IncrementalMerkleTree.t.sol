// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Test} from "forge-std/Test.sol";
import {stdJson} from "forge-std/StdJson.sol";
import {MerkleProof} from "@openzeppelin/contracts/utils/cryptography/MerkleProof.sol";
import {IncrementalMerkleTree} from "../src/lib/IncrementalMerkleTree.sol";
import {Withdrawal, WithdrawalHash} from "../src/lib/Withdrawals.sol";
import {TreeBuilder} from "./utils/TreeBuilder.sol";

contract TreeHarness {
    using IncrementalMerkleTree for IncrementalMerkleTree.Tree;

    IncrementalMerkleTree.Tree internal tree; // slot 0 = count

    function insert(bytes32 leaf) external returns (uint256) {
        return tree.insert(leaf);
    }

    function root() external view returns (bytes32) {
        return tree.root();
    }

    function count() external view returns (uint256) {
        return tree.count;
    }
}

contract IncrementalMerkleTreeTest is Test {
    using stdJson for string;

    TreeHarness internal h;

    function setUp() public {
        h = new TreeHarness();
    }

    function _leaf(uint256 i) internal pure returns (bytes32) {
        return keccak256(abi.encode("leaf", i));
    }

    function test_emptyRootIsZeroHashAtDepth() public view {
        assertEq(h.root(), IncrementalMerkleTree.zeroHash(20));
        assertEq(h.root(), TreeBuilder.zero(20));
    }

    function test_zeroHashesChain() public pure {
        assertEq(IncrementalMerkleTree.zeroHash(0), bytes32(0));
        assertEq(IncrementalMerkleTree.zeroHash(1), TreeBuilder.pair(bytes32(0), bytes32(0)));
        assertEq(
            IncrementalMerkleTree.zeroHash(3),
            TreeBuilder.pair(IncrementalMerkleTree.zeroHash(2), IncrementalMerkleTree.zeroHash(2))
        );
    }

    function test_hashPairIsCommutative() public pure {
        bytes32 a = keccak256("a");
        bytes32 b = keccak256("b");
        assertEq(IncrementalMerkleTree.hashPair(a, b), IncrementalMerkleTree.hashPair(b, a));
        assertEq(IncrementalMerkleTree.hashPair(a, b), keccak256(abi.encodePacked(a < b ? a : b, a < b ? b : a)));
    }

    function test_insertReturnsSequentialIndexAndCount() public {
        for (uint256 i = 0; i < 5; ++i) {
            assertEq(h.insert(_leaf(i)), i);
            assertEq(h.count(), i + 1);
        }
    }

    function test_rootMatchesReferenceAtEverySizeUpTo70() public {
        bytes32[] memory all = new bytes32[](70);
        for (uint256 i = 0; i < 70; ++i) {
            all[i] = _leaf(i);
            h.insert(all[i]);
            assertEq(h.root(), TreeBuilder.root(TreeBuilder.head(all, i + 1)), "size mismatch");
        }
    }

    function test_proofsVerifyWithOpenZeppelinAtEverySizeUpTo40() public {
        bytes32[] memory all = new bytes32[](40);
        for (uint256 n = 1; n <= 40; ++n) {
            all[n - 1] = _leaf(n - 1);
            h.insert(all[n - 1]);
            bytes32[] memory leaves = TreeBuilder.head(all, n);
            for (uint256 i = 0; i < n; ++i) {
                assertTrue(MerkleProof.verify(TreeBuilder.proof(leaves, i), h.root(), leaves[i]));
            }
        }
    }

    function testFuzz_rootAndProofsMatchReference(uint8 n, uint256 seed) public {
        n = uint8(bound(n, 1, 150));
        bytes32[] memory all = new bytes32[](n);
        for (uint256 i = 0; i < n; ++i) {
            all[i] = keccak256(abi.encode(seed, i));
            h.insert(all[i]);
        }
        bytes32 root = h.root();
        assertEq(root, TreeBuilder.root(all));
        uint256 pick = seed % n;
        assertTrue(MerkleProof.verify(TreeBuilder.proof(all, pick), root, all[pick]));
    }

    function testFuzz_flippedSiblingNeverVerifies(uint8 n, uint256 seed, uint8 which, bytes32 other) public pure {
        n = uint8(bound(n, 2, 100));
        bytes32[] memory all = new bytes32[](n);
        for (uint256 i = 0; i < n; ++i) {
            all[i] = keccak256(abi.encode(seed, i));
        }
        uint256 pick = seed % n;
        bytes32[] memory p = TreeBuilder.proof(all, pick);
        uint256 level = which % 20;
        vm.assume(other != p[level]);
        p[level] = other;
        assertFalse(MerkleProof.verify(p, TreeBuilder.root(all), all[pick]));
    }

    function test_leafCannotBeConfusedWithInnerNode() public pure {
        // A leaf preimage is 128 bytes, an inner node preimage 64 bytes: even if an attacker picks the two
        // children of an inner node as "withdrawal fields", the resulting leaf hash differs from the node.
        bytes32 a = keccak256("left");
        bytes32 b = keccak256("right");
        bytes32 inner = TreeBuilder.pair(a, b);
        Withdrawal memory fake = Withdrawal({
            id: uint256(a), sender: address(uint160(uint256(b))), target: address(uint160(uint256(b))), amount: 0
        });
        assertTrue(WithdrawalHash.leaf(fake) != inner);
        assertEq(abi.encode(fake.id, fake.sender, fake.target, fake.amount).length, 128);
    }

    function test_leafOverloadsAgree() public pure {
        Withdrawal memory w = Withdrawal({id: 3, sender: address(0xA1), target: address(0xB2), amount: 77});
        assertEq(WithdrawalHash.leaf(w), WithdrawalHash.leaf(3, address(0xA1), address(0xB2), 77));
    }

    function test_RevertWhen_treeIsFull() public {
        vm.store(address(h), bytes32(uint256(0)), bytes32(IncrementalMerkleTree.MAX_LEAVES));
        vm.expectRevert(IncrementalMerkleTree.TreeFull.selector);
        h.insert(_leaf(0));
    }

    function test_lastAcceptedLeafStillProducesAValidRoot() public {
        // Jump the counter to the last free slot: the insert must succeed (frontier bit set at height 0).
        vm.store(address(h), bytes32(uint256(0)), bytes32(IncrementalMerkleTree.MAX_LEAVES - 1));
        assertEq(h.insert(_leaf(0)), IncrementalMerkleTree.MAX_LEAVES - 1);
        assertEq(h.count(), IncrementalMerkleTree.MAX_LEAVES);
    }

    /// @dev Differential test against the file the Java tests also read. The file is produced by
    ///      `forge script script/GenerateTreeVectors.s.sol`; skipped if it has not been generated yet.
    function test_matchesCommittedVectors() public {
        string memory path = "../testdata/tree-vectors.json";
        if (!vm.exists(path)) {
            vm.skip(true);
        }
        string memory json = vm.readFile(path);
        bytes32[] memory leaves = json.readBytes32Array(".leaves");
        bytes32[] memory roots = json.readBytes32Array(".roots");
        assertEq(roots.length, leaves.length + 1);
        for (uint256 n = 0; n <= leaves.length; ++n) {
            assertEq(roots[n], TreeBuilder.root(TreeBuilder.head(leaves, n)), "root vector");
        }
    }
}
