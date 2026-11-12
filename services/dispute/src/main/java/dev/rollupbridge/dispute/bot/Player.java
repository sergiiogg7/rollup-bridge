package dev.rollupbridge.dispute.bot;

import dev.rollupbridge.dispute.game.GameChain;

/** One participant of a game. {@link #act} makes AT MOST one move, and only if it is this player's turn. */
public interface Player {
    String address();

    /** Looks at game {@code id}; if it is this player's turn and it chooses to move, sends one move and returns true. */
    boolean act(GameChain chain, long id);
}
