package dev.rollupbridge.dispute;

import static org.assertj.core.api.Assertions.assertThat;

import dev.rollupbridge.core.Abi;
import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.core.Keccak256;
import dev.rollupbridge.dispute.evm.EvmGameChain;
import dev.rollupbridge.dispute.game.GameChain;
import dev.rollupbridge.dispute.game.GameException;
import dev.rollupbridge.dispute.game.InMemoryGameChain;
import dev.rollupbridge.dispute.vm.Program;
import dev.rollupbridge.dispute.vm.Trace;
import dev.rollupbridge.dispute.vm.VmState;
import dev.rollupbridge.testkit.Accounts;
import dev.rollupbridge.testkit.Anvil;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Differential test of the GAME LOGIC: the same random sequence of moves (legal and illegal, by the right and the
 * wrong accounts, on time and late) is applied to the deployed Solidity contract and to the in-memory Java model, with
 * one shared clock. After every move both must agree on whether it was accepted, on the exact custom error when it was
 * not, on every game's full state and on every account's credits. This is what makes the fast randomized tests on the
 * model meaningful for the contract.
 */
class ModelVsContractTest {
    private static final List<String> ACCOUNTS = List.of(
            Accounts.PROPOSER.address(), Accounts.CHALLENGER.address(), Accounts.ALICE.address(), Accounts.MALLORY.address());
    private static final Program PROGRAM = Program.sumLoop(1000);

    private static Anvil anvil;

    @BeforeAll
    static void start() {
        anvil = Live.anvil();
    }

    @Test
    @DisplayName("3 random sequences of 120 moves each: contract and model agree on every outcome, error name, state and balance")
    void randomSequencesBehaveIdentically() {
        for (long seed : new long[] {1, 2, 3}) {
            runSequence(seed, 120);
        }
    }

    private void runSequence(long seed, int actions) {
        Random rnd = new Random(seed);
        Trace truth = new Trace(PROGRAM, VmState.zero());
        EvmGameChain real = Live.deployGame(anvil, PROGRAM);
        long clock = real.now() + 10;
        InMemoryGameChain model = new InMemoryGameChain(PROGRAM.hash(), Live.MOVE_TIMEOUT, Live.PROPOSER_BOND, Live.CHALLENGER_BOND, clock);

        List<String> log = new ArrayList<>();
        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < actions; i++) {
            // one shared clock: mostly small steps, now and then enough to expire every pending clock
            clock += rnd.nextInt(25) == 0 ? Live.MOVE_TIMEOUT / 2 + rnd.nextInt((int) Live.MOVE_TIMEOUT) : 1 + rnd.nextInt(30);
            real.setNextBlockTimestamp(clock);
            long target = clock;
            model.advanceTime(target - model.now());

            long count = model.gameCount();
            long id = count == 0 || rnd.nextInt(12) == 0 ? count + rnd.nextInt(2) : rnd.nextInt((int) count);
            String sender = ACCOUNTS.get(rnd.nextInt(ACCOUNTS.size()));
            int kind = count < 6 && rnd.nextInt(6) == 0 ? 0 : 1 + rnd.nextInt(5);
            if (id < count && rnd.nextInt(10) < 7) { // most of the time play the move the game is waiting for, by the right account
                var v = model.game(id);
                switch (v.phase()) {
                    case AWAITING_BISECT -> {
                        kind = 2;
                        sender = v.proposer();
                    }
                    case AWAITING_RESPONSE -> {
                        kind = 3;
                        sender = v.challenger();
                    }
                    case AWAITING_STEP -> kind = 4;
                    case AWAITING_CHALLENGE -> kind = 1;
                    default -> { }
                }
            }
            String who = sender;
            String what;
            Consumer<GameChain> move;
            switch (kind) {
                case 0 -> {
                    long steps = 1 + rnd.nextInt(24);
                    Bytes32 claim = rnd.nextInt(3) == 0 ? junk(rnd) : truth.hashAt(steps);
                    what = "createGame by " + who + " steps=" + steps;
                    move = c -> c.createGame(who, truth.initial().hash(), steps, claim);
                }
                case 1 -> {
                    what = "challenge " + id + " by " + who;
                    move = c -> c.challenge(who, id);
                }
                case 2 -> {
                    long expected = id < count ? model.game(id).expectedStep() : 0;
                    long step = switch (rnd.nextInt(5)) {
                        case 0 -> expected + 1;
                        case 1 -> Math.max(0, expected - 1);
                        default -> expected;
                    };
                    Bytes32 h = rnd.nextInt(3) == 0 ? junk(rnd) : truth.hashAt(step);
                    what = "bisect " + id + " step " + step + " by " + who;
                    move = c -> c.bisect(who, id, step, h);
                }
                case 3 -> {
                    boolean agree = rnd.nextBoolean();
                    what = "respond " + id + " agree=" + agree + " by " + who;
                    move = c -> c.respond(who, id, agree);
                }
                case 4 -> {
                    long lo = id < count ? model.game(id).lo() : 0;
                    VmState pre = switch (rnd.nextInt(6)) {
                        case 0 -> truth.at(lo + 1);
                        case 1 -> truth.at(lo).withReg(1, rnd.nextLong());
                        default -> truth.at(lo);
                    };
                    Program program = rnd.nextInt(8) == 0 ? Program.sumLoop(999) : PROGRAM;
                    what = "step " + id + " by " + who + (program == PROGRAM ? "" : " (wrong program)");
                    move = c -> c.step(who, id, program, pre);
                }
                default -> {
                    what = "claimTimeout " + id + " by " + who;
                    move = c -> c.claimTimeout(who, id);
                }
            }
            String a = attempt(model, move);
            String b = attempt(real, move);
            log.add(i + " t=" + clock + " " + what + " -> model " + a + " | contract " + b);
            assertThat(b).as("move %d (%s)%nrecent moves:%n%s", i, what, String.join("\n", log.subList(Math.max(0, log.size() - 6), log.size()))).isEqualTo(a);
            if (a.equals("ok")) {
                accepted++;
            } else {
                rejected++;
            }
            compareStates(model, real, "after move " + i + " (" + what + ")");
        }
        // the sequence must have exercised both outcomes a lot, or it proves nothing
        assertThat(accepted).as("accepted moves in sequence %d", seed).isGreaterThan(actions / 8);
        assertThat(rejected).as("rejected moves in sequence %d", seed).isGreaterThan(actions / 8);
    }

    private static String attempt(GameChain chain, Consumer<GameChain> move) {
        try {
            move.accept(chain);
            return "ok";
        } catch (GameException e) {
            return e.error();
        }
    }

    private static void compareStates(InMemoryGameChain model, EvmGameChain real, String context) {
        assertThat(real.gameCount()).as(context).isEqualTo(model.gameCount());
        for (long id = 0; id < model.gameCount(); id++) {
            assertThat(real.game(id)).as("game %d %s", id, context).isEqualTo(model.game(id));
        }
        for (String account : ACCOUNTS) {
            assertThat(real.credits(account)).as("credits of %s %s", account, context).isEqualTo(model.credits(account));
        }
    }

    private static Bytes32 junk(Random rnd) {
        return Bytes32.of(Keccak256.hash(Abi.encode(List.of(Abi.uint(rnd.nextLong() & Long.MAX_VALUE)))));
    }
}
