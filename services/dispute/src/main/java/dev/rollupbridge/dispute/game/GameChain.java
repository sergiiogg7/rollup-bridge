package dev.rollupbridge.dispute.game;

import dev.rollupbridge.core.Bytes32;
import dev.rollupbridge.dispute.vm.Program;
import dev.rollupbridge.dispute.vm.VmState;

/**
 * The moves of the bisection game, as a port. Two implementations: {@link InMemoryGameChain}, a faithful Java model of
 * the contract that plays thousands of games per second, and {@code EvmGameChain}, which sends real transactions to
 * the deployed {@code BisectionGame}. The bots only know this interface.
 *
 * <p>A refused move throws {@link GameException} carrying the Solidity custom-error name.
 */
public interface GameChain {
    /** Opens a game; the sender is the proposer and bonds {@link #proposerBond()}. Returns the game id. */
    long createGame(String sender, Bytes32 initialHash, long steps, Bytes32 claimedHash);

    /** Disputes the claim; the sender bonds {@link #challengerBond()}. */
    void challenge(String sender, long id);

    /** Proposer move: the state hash at the midpoint {@code step}. */
    void bisect(String sender, long id, long step, Bytes32 midHash);

    /** Challenger move: agree with the proposer's midpoint hash or not. */
    void respond(String sender, long id, boolean agree);

    /** One-step proof: anybody, with the program and the agreed pre-state. */
    void step(String sender, long id, Program program, VmState preState);

    /** Ends a game whose current move deadline has passed. */
    void claimTimeout(String sender, long id);

    /** Pays out the sender's credits; returns the amount in wei. */
    java.math.BigInteger withdraw(String sender);

    GameView game(long id);

    long gameCount();

    java.math.BigInteger credits(String account);

    java.math.BigInteger proposerBond();

    java.math.BigInteger challengerBond();

    /** Seconds each party has for each move. */
    long moveTimeout();

    /** The chain's current time in seconds. */
    long now();

    /** Moves the chain clock forward (a test hook: {@code evm_increaseTime} on Anvil). */
    void advanceTime(long seconds);
}
