// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {Test} from "forge-std/Test.sol";
import {ToyVM, VMState} from "../../src/disputes/ToyVM.sol";
import {Asm} from "./utils/Asm.sol";
import {VmHarness} from "./utils/VmHarness.sol";

contract ToyVMTest is Test {
    VmHarness internal h = new VmHarness();

    function _state(uint64 pc, uint64 r0, uint64 r1, uint64 r2, uint64 r3) internal pure returns (VMState memory) {
        VMState memory s;
        s.pc = pc;
        s.regs[0] = r0;
        s.regs[1] = r1;
        s.regs[2] = r2;
        s.regs[3] = r3;
        return s;
    }

    function _assertState(VMState memory s, VMState memory e) internal pure {
        assertEq(ToyVM.hashState(s), ToyVM.hashState(e));
    }

    // ----- hashing -----

    function test_hashState_isKeccakOfFiveWords() public pure {
        VMState memory s = _state(3, 1, 2, 3, 4);
        assertEq(ToyVM.hashState(s), keccak256(abi.encode(uint256(3), uint256(1), uint256(2), uint256(3), uint256(4))));
    }

    /// @dev Pinned vector (`cast keccak` of 160 zero bytes), repeated in the Java test `ToyVmTest`: both sides
    ///      must agree on the encoding.
    function test_hashState_zeroStateVector() public pure {
        assertEq(ToyVM.hashState(Asm.zeroState()), keccak256(new bytes(160)));
        assertEq(ToyVM.hashState(Asm.zeroState()), 0xdfded4ed5ac76ba7379cfe7b3b0f53e768dca8d45a34854e649cfc3c18cbd9cd);
    }

    // ----- one test per opcode -----

    function test_step_mov() public pure {
        bytes memory p = Asm.mov(2, 77);
        _assertState(ToyVM.step(p, _state(0, 1, 1, 1, 1)), _state(1, 1, 1, 77, 1));
    }

    function test_step_add() public pure {
        bytes memory p = Asm.add(0, 1);
        _assertState(ToyVM.step(p, _state(0, 5, 7, 0, 0)), _state(1, 12, 7, 0, 0));
    }

    function test_step_sub() public pure {
        bytes memory p = Asm.sub(3, 2);
        _assertState(ToyVM.step(p, _state(0, 0, 0, 4, 10)), _state(1, 0, 0, 4, 6));
    }

    function test_step_mul() public pure {
        bytes memory p = Asm.mul(1, 2);
        _assertState(ToyVM.step(p, _state(0, 0, 6, 7, 0)), _state(1, 0, 42, 7, 0));
    }

    function test_step_arithmeticWrapsModulo2Pow64() public pure {
        _assertState(ToyVM.step(Asm.add(0, 1), _state(0, type(uint64).max, 2, 0, 0)), _state(1, 1, 2, 0, 0));
        _assertState(ToyVM.step(Asm.sub(0, 1), _state(0, 1, 2, 0, 0)), _state(1, type(uint64).max, 2, 0, 0));
        _assertState(
            ToyVM.step(Asm.mul(0, 1), _state(0, type(uint64).max, 2, 0, 0)), _state(1, type(uint64).max - 1, 2, 0, 0)
        );
    }

    function test_step_arithmeticWithSameRegisterOnBothSides() public pure {
        _assertState(ToyVM.step(Asm.add(0, 0), _state(0, 21, 0, 0, 0)), _state(1, 42, 0, 0, 0));
        _assertState(ToyVM.step(Asm.sub(0, 0), _state(0, 21, 0, 0, 0)), _state(1, 0, 0, 0, 0));
    }

    function test_step_jnzTakenWhenRegisterNonZero() public pure {
        _assertState(ToyVM.step(Asm.jnz(2, 9), _state(0, 0, 0, 1, 0)), _state(9, 0, 0, 1, 0));
    }

    function test_step_jnzFallsThroughWhenRegisterZero() public pure {
        _assertState(ToyVM.step(Asm.jnz(2, 9), _state(0, 5, 5, 0, 5)), _state(1, 5, 5, 0, 5));
    }

    function test_step_haltIsAFixedPoint() public pure {
        VMState memory s = _state(0, 1, 2, 3, 4);
        _assertState(ToyVM.step(Asm.halt(), s), _state(0, 1, 2, 3, 4));
    }

    // ----- totality: nothing can make `step` revert -----

    function test_step_pcPastTheEndBehavesLikeHalt() public pure {
        bytes memory p = Asm.mov(0, 1);
        _assertState(ToyVM.step(p, _state(1, 5, 6, 7, 8)), _state(1, 5, 6, 7, 8));
        _assertState(ToyVM.step(p, _state(type(uint64).max, 5, 6, 7, 8)), _state(type(uint64).max, 5, 6, 7, 8));
    }

    function test_step_emptyProgramIsAlwaysHalted() public pure {
        _assertState(ToyVM.step("", _state(0, 1, 2, 3, 4)), _state(0, 1, 2, 3, 4));
    }

    function test_step_unknownOpcodeTraps() public pure {
        _assertState(ToyVM.step(Asm.ins(6, 0, 0, 1), _state(0, 1, 2, 3, 4)), _state(0, 1, 2, 3, 4));
        _assertState(ToyVM.step(Asm.ins(255, 0, 0, 1), _state(0, 1, 2, 3, 4)), _state(0, 1, 2, 3, 4));
    }

    function test_step_badDestinationRegisterTraps() public pure {
        VMState memory s = _state(0, 1, 2, 3, 4);
        _assertState(ToyVM.step(Asm.mov(4, 9), s), _state(0, 1, 2, 3, 4));
        _assertState(ToyVM.step(Asm.add(4, 0), s), _state(0, 1, 2, 3, 4));
        _assertState(ToyVM.step(Asm.jnz(200, 0), s), _state(0, 1, 2, 3, 4));
    }

    function test_step_badSourceRegisterTraps() public pure {
        VMState memory s = _state(0, 1, 2, 3, 4);
        _assertState(ToyVM.step(Asm.add(0, 4), s), _state(0, 1, 2, 3, 4));
        _assertState(ToyVM.step(Asm.sub(0, 255), s), _state(0, 1, 2, 3, 4));
        _assertState(ToyVM.step(Asm.mul(0, 7), s), _state(0, 1, 2, 3, 4));
    }

    function test_step_trailingPartialInstructionIsIgnored() public pure {
        // 11 bytes of MOV r0,5 followed by 3 junk bytes: only one whole instruction exists.
        bytes memory p = bytes.concat(Asm.mov(0, 5), hex"010203");
        _assertState(ToyVM.step(p, _state(0, 0, 0, 0, 0)), _state(1, 5, 0, 0, 0));
        _assertState(ToyVM.step(p, _state(1, 0, 0, 0, 0)), _state(1, 0, 0, 0, 0));
    }

    function test_step_immediateIsBigEndianAllEightBytes() public pure {
        _assertState(
            ToyVM.step(Asm.mov(0, 0x0102030405060708), _state(0, 0, 0, 0, 0)), _state(1, 0x0102030405060708, 0, 0, 0)
        );
        _assertState(
            ToyVM.step(Asm.mov(0, type(uint64).max), _state(0, 0, 0, 0, 0)), _state(1, type(uint64).max, 0, 0, 0)
        );
    }

    // ----- whole programs -----

    function test_run_sumLoopComputesTriangularNumber() public pure {
        bytes memory p = Asm.sumLoop(10);
        VMState memory end = Asm.run(p, Asm.zeroState(), 3 + 3 * 10);
        assertEq(end.regs[1], 55);
        assertEq(end.regs[0], 0);
        assertEq(end.pc, 6); // sitting on HALT
        // HALT is a fixed point: more steps change nothing.
        assertEq(ToyVM.hashState(Asm.run(p, Asm.zeroState(), 500)), ToyVM.hashState(end));
    }

    function test_run_factorial() public pure {
        VMState memory end = Asm.run(Asm.factorial(10), Asm.zeroState(), 200);
        assertEq(end.regs[1], 3_628_800);
    }

    // ----- well-formedness helper -----

    function test_isWellFormed() public pure {
        assertFalse(ToyVM.isWellFormed(""));
        assertTrue(ToyVM.isWellFormed(Asm.halt()));
        assertFalse(ToyVM.isWellFormed(new bytes(12)));
        assertTrue(ToyVM.isWellFormed(new bytes(11 * 64)));
        assertFalse(ToyVM.isWellFormed(new bytes(11 * 65)));
    }

    // ----- fuzz -----

    /// @dev `step` is total: arbitrary program bytes and arbitrary states never revert, and at most one
    ///      register changes.
    function testFuzz_step_neverRevertsAndOnlyTouchesOneRegister(bytes memory program, uint64 pc, uint64[4] memory regs)
        public
        pure
    {
        VMState memory s = VMState({pc: pc, regs: regs});
        VMState memory n = ToyVM.step(program, s);
        uint256 changed;
        for (uint256 i = 0; i < 4; ++i) {
            if (n.regs[i] != regs[i]) ++changed;
        }
        assertLe(changed, 1);
        // the input state is never mutated
        assertEq(s.pc, pc);
    }

    function testFuzz_step_harnessMatchesLibrary(bytes memory program, uint64 pc, uint64[4] memory regs) public view {
        VMState memory s = VMState({pc: pc, regs: regs});
        assertEq(ToyVM.hashState(h.step(program, s)), ToyVM.hashState(ToyVM.step(program, s)));
        assertEq(h.hashState(s), ToyVM.hashState(s));
        assertEq(h.isWellFormed(program), ToyVM.isWellFormed(program));
    }
}
