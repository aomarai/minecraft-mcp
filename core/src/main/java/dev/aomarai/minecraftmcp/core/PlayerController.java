package dev.aomarai.minecraftmcp.core;

import java.util.concurrent.CompletableFuture;

/**
 * Client adapters only: the player of this game client, and nobody else. Each call does what the player's own keys
 * and mouse would do, so the server still decides what is allowed: reach, permissions and spawn protection all
 * apply. There is no way to name another player.
 *
 * <p>Every call is bounded and ends: movement and digging give up after a fixed number of game ticks, and a call that
 * is cancelled or times out releases the keys it holds. Each answers with a sentence saying what happened, or fails
 * with a message that names the problem.</p>
 */
public interface PlayerController extends GamePort {

    /** Walks toward a point, turning and jumping over one-block steps, until close to it or out of time. */
    CompletableFuture<String> moveTo(double x, double y, double z);

    /** Turns to face a point. */
    CompletableFuture<String> lookAt(double x, double y, double z);

    /** Jumps once. */
    CompletableFuture<String> jump();

    /** Digs the block at a position within reach, until it breaks or time runs out. */
    CompletableFuture<String> breakBlock(int x, int y, int z);

    /** Places the block in the main hand at an empty position within reach, against a neighbouring block. */
    CompletableFuture<String> placeBlock(int x, int y, int z);

    /** Uses the main hand on the block at a position within reach: opens a door or chest, presses a button. */
    CompletableFuture<String> interact(int x, int y, int z);
}
