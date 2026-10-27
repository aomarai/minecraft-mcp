package dev.aomarai.minecraftmcp.fabric;

import dev.aomarai.minecraftmcp.core.PlayerController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Drives the local player with the same inputs a person would: the forward and jump keys, the view direction, and
 * the game mode's own dig and use calls. The server therefore still decides what happens (reach, permissions, spawn
 * protection). One action runs at a time, a newer one interrupts the older, and every action ends: it finishes, fails
 * with a message, or gives up after a fixed number of ticks, and in each case lets go of the keys. All of it runs on
 * the client's main thread, one step per client tick, so the game never waits for it.
 */
final class FabricClientPlayer implements PlayerController {

    /** Actions that take time give up after this many ticks (five seconds). */
    private static final int MAX_TICKS = 100;
    /** Close enough to a target to count as arrived, in blocks. */
    private static final double ARRIVED = 0.4;
    /** The vanilla reach for blocks, in blocks; the server applies its own check as well. */
    private static final double REACH = 4.5;

    /** One thing the player is doing. {@code tick} returns the answer when done, or null to keep going. */
    private interface Action {
        String tick(Minecraft minecraft, LocalPlayer player, int tick);
    }

    private record Running(Action action, CompletableFuture<String> result) {
    }

    /** Touched only on the client's main thread. */
    private Running running;
    private int ticks;

    @Override
    public CompletableFuture<String> moveTo(double x, double y, double z) {
        Vec3 target = new Vec3(x, y, z);
        return start((minecraft, player, tick) -> {
            double dx = x - player.getX();
            double dz = z - player.getZ();
            if (Math.hypot(dx, dz) < ARRIVED) {
                return "arrived at " + position(player);
            }
            if (tick >= MAX_TICKS) {
                throw new IllegalStateException(String.format(Locale.ROOT,
                        "did not arrive within %d ticks; now at %s, %.1f blocks from the target", MAX_TICKS,
                        position(player), Math.hypot(dx, dz)));
            }
            face(player, target);
            player.setXRot(0);
            minecraft.options.keyUp.setDown(true);
            // Hop over a one-block step or a fence post the way a person would.
            minecraft.options.keyJump.setDown(player.horizontalCollision && player.onGround());
            return null;
        });
    }

    @Override
    public CompletableFuture<String> lookAt(double x, double y, double z) {
        return start((minecraft, player, tick) -> {
            face(player, new Vec3(x, y, z));
            return String.format(Locale.ROOT, "facing yaw %.1f, pitch %.1f", player.getYRot(), player.getXRot());
        });
    }

    @Override
    public CompletableFuture<String> jump() {
        return start((minecraft, player, tick) -> {
            if (tick == 0) {
                if (!player.onGround()) {
                    throw new IllegalStateException("cannot jump: the player is not on the ground");
                }
                minecraft.options.keyJump.setDown(true);
                return null;
            }
            return "jumped";
        });
    }

    @Override
    public CompletableFuture<String> breakBlock(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return start((minecraft, player, tick) -> {
            requireReach(player, pos);
            if (minecraft.level.getBlockState(pos).isAir()) {
                if (tick == 0) {
                    throw new IllegalStateException("there is no block at " + describe(pos));
                }
                minecraft.gameMode.stopDestroyBlock();
                return "broke the block at " + describe(pos);
            }
            if (tick >= MAX_TICKS) {
                throw new IllegalStateException("the block at " + describe(pos) + " did not break within " + MAX_TICKS
                        + " ticks");
            }
            face(player, Vec3.atCenterOf(pos));
            Direction side = sideFacing(player, pos);
            if (tick == 0) {
                minecraft.gameMode.startDestroyBlock(pos, side);
            } else {
                minecraft.gameMode.continueDestroyBlock(pos, side);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<String> placeBlock(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return start((minecraft, player, tick) -> {
            requireReach(player, pos);
            if (!minecraft.level.getBlockState(pos).canBeReplaced()) {
                throw new IllegalStateException("there is already a block at " + describe(pos));
            }
            if (!(player.getMainHandItem().getItem() instanceof BlockItem)) {
                throw new IllegalStateException("the main hand holds no block");
            }
            // The registry id, read before the block is placed: afterwards the last block of a stack is gone, and the
            // item's own name is text its owner chose (an anvil rename), which must not reach the model.
            String placed = BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()).toString();
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = pos.relative(direction);
                if (!minecraft.level.getBlockState(neighbour).isAir()
                        && !minecraft.level.getBlockState(neighbour).canBeReplaced()) {
                    use(minecraft, player, neighbour, direction.getOpposite());
                    return "placed " + placed + " at " + describe(pos);
                }
            }
            throw new IllegalStateException("there is no block next to " + describe(pos) + " to place against");
        });
    }

    @Override
    public CompletableFuture<String> interact(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return start((minecraft, player, tick) -> {
            requireReach(player, pos);
            if (minecraft.level.getBlockState(pos).isAir()) {
                throw new IllegalStateException("there is no block at " + describe(pos));
            }
            Direction side = sideFacing(player, pos);
            use(minecraft, player, pos, side);
            return "used the main hand on the block at " + describe(pos);
        });
    }

    /** Called once per client tick, on the main thread. */
    void tick(Minecraft minecraft) {
        Running current = running;
        if (current == null) {
            return;
        }
        if (current.result().isDone() || minecraft.player == null || minecraft.level == null) {
            // Cancelled or timed out by the caller, or the world went away: stop and let go of the keys.
            finish(minecraft, current, null, new IllegalStateException("the player is no longer in a world"));
            return;
        }
        try {
            String answer = current.action().tick(minecraft, minecraft.player, ticks++);
            if (answer != null) {
                finish(minecraft, current, answer, null);
            }
        } catch (RuntimeException e) {
            finish(minecraft, current, null, e);
        }
    }

    private CompletableFuture<String> start(Action action) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (result.isDone()) {
                return;
            }
            if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
                result.completeExceptionally(new IllegalStateException("the player is not in a world"));
                return;
            }
            if (ClientCompat.screen(minecraft) != null) {
                result.completeExceptionally(new IllegalStateException(
                        "a screen is open; the player cannot act until it is closed"));
                return;
            }
            Running previous = running;
            if (previous != null) {
                finish(minecraft, previous, null, new IllegalStateException("interrupted by a newer action"));
            }
            running = new Running(action, result);
            ticks = 0;
        });
        return result;
    }

    private void finish(Minecraft minecraft, Running done, String answer, RuntimeException failure) {
        minecraft.options.keyUp.setDown(false);
        minecraft.options.keyJump.setDown(false);
        if (minecraft.gameMode != null) {
            minecraft.gameMode.stopDestroyBlock();
        }
        if (running == done) {
            running = null;
        }
        if (failure != null) {
            done.result().completeExceptionally(failure);
        } else if (answer != null) {
            done.result().complete(answer);
        }
    }

    private static void use(Minecraft minecraft, LocalPlayer player, BlockPos pos, Direction face) {
        Vec3 point = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        InteractionResult result = minecraft.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                new BlockHitResult(point, face, pos, false));
        if (!result.consumesAction()) {
            throw new IllegalStateException("the game did not accept that: " + result);
        }
    }

    private static void requireReach(LocalPlayer player, BlockPos pos) {
        double distance = player.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
        if (distance > REACH) {
            throw new IllegalStateException(String.format(Locale.ROOT, "%s is %.1f blocks away, beyond the reach of %.1f",
                    describe(pos), distance, REACH));
        }
    }

    /** The face of the block that looks toward the player. */
    private static Direction sideFacing(LocalPlayer player, BlockPos pos) {
        Vec3 offset = player.getEyePosition().subtract(Vec3.atCenterOf(pos));
        double ax = Math.abs(offset.x);
        double ay = Math.abs(offset.y);
        double az = Math.abs(offset.z);
        if (ay >= ax && ay >= az) {
            return offset.y > 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return offset.x > 0 ? Direction.EAST : Direction.WEST;
        }
        return offset.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static void face(LocalPlayer player, Vec3 target) {
        Vec3 from = player.getEyePosition();
        double dx = target.x - from.x;
        double dy = target.y - from.y;
        double dz = target.z - from.z;
        player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        player.setXRot((float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
    }

    private static String position(LocalPlayer player) {
        return String.format(Locale.ROOT, "%.1f, %.1f, %.1f", player.getX(), player.getY(), player.getZ());
    }

    private static String describe(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }
}
