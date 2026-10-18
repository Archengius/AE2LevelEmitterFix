package me.archengius.levelemitterfix.test;

import appeng.api.parts.IPartHost;
import appeng.api.parts.PartHelper;
import appeng.api.util.AEColor;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEParts;
import appeng.parts.automation.AbstractLevelEmitterPart;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Reproduces a lost level emitter neighbor update with a real chunk unload and reload:
 * <ol>
 * <li>An emitter on a cable bus powers a redstone lamp, all in one force-loaded chunk far from the test area
 * (whose chunks the gametest framework keeps loaded).</li>
 * <li>Remove the force-load ticket. In {@link ChunkEvent.Unload}, which Forge fires right before the chunk is saved,
 * flip the emitter off: the neighbor update gets queued, the chunk is saved with the new emitter state and the lit
 * lamp, and the queued update is dropped because the chunk is gone by the time AE2 processes it.</li>
 * <li>Wait a few ticks so AE2 really drops it.</li>
 * <li>Force-load the chunk again; it must come back with the lamp lit while the emitter outputs nothing.</li>
 * <li>Succeed if the lamp turns off, i.e. the neighbor update was sent again.</li>
 * </ol>
 */
@Mod("levelemitterfixtest")
@GameTestHolder("levelemitterfixtest")
@PrefixGameTestTemplate(false)
public class ChunkReloadTest {
    private static final BlockPos BUS = new BlockPos(4104, 100, 4104);
    private static final BlockPos LAMP = BUS.above();
    private static final ChunkPos CHUNK = new ChunkPos(BUS);
    private static final int PHASE_TIMEOUT_TICKS = 200;

    private static Scenario active;

    public ChunkReloadTest() {
        MinecraftForge.EVENT_BUS.addListener((ChunkEvent.Unload e) -> {
            if (active != null) {
                active.onChunkUnload(e);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1000)
    public static void lostNeighborUpdateIsResentAfterChunkReload(GameTestHelper helper) {
        var scenario = new Scenario(helper);
        active = scenario;
        helper.onEachTick(scenario::tick);
    }

    private enum Phase { BUILD, WAIT_LIT, WAIT_UNLOAD, WAIT_GONE, COOLDOWN, WAIT_RELOAD, VERIFY }

    private static class Scenario {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private Phase phase = Phase.BUILD;
        private int ticks;

        Scenario(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
        }

        void onChunkUnload(ChunkEvent.Unload e) {
            if (phase == Phase.WAIT_UNLOAD && e.getChunk() instanceof LevelChunk chunk && chunk.getPos().equals(CHUNK)) {
                // The chunk is already flagged unloaded here, so go through it directly instead of the level
                var host = (IPartHost) chunk.getBlockEntity(BUS);
                ((AbstractLevelEmitterPart) host.getPart(Direction.UP)).setReportingValue(10);
                next(Phase.WAIT_GONE);
            }
        }

        void tick() {
            ticks++;
            switch (phase) {
                case BUILD -> {
                    level.setChunkForced(CHUNK.x, CHUNK.z, true);
                    if (!isLoaded()) {
                        timeout("chunk never loaded");
                        return;
                    }
                    level.setBlockAndUpdate(BUS.east(), AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
                    PartHelper.setPart(level, BUS, null, null, AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
                    PartHelper.setPart(level, BUS, Direction.UP, null, AEParts.LEVEL_EMITTER.asItem());
                    level.setBlockAndUpdate(LAMP, Blocks.REDSTONE_LAMP.defaultBlockState());
                    next(Phase.WAIT_LIT);
                }
                case WAIT_LIT -> {
                    // Threshold 0 on an empty network: the emitter turns on once the grid is up
                    if (lampLit() && level.hasNeighborSignal(LAMP)) {
                        level.setChunkForced(CHUNK.x, CHUNK.z, false);
                        next(Phase.WAIT_UNLOAD);
                    } else {
                        timeout("lamp never lit up");
                    }
                }
                case WAIT_UNLOAD -> timeout("chunk never unloaded");
                case WAIT_GONE -> {
                    if (!isLoaded()) {
                        next(Phase.COOLDOWN);
                    }
                }
                case COOLDOWN -> {
                    // Let AE2 process (and drop) the queued update while the chunk is gone. Reloading right away can
                    // bring the chunk back before AE2's next queue pass, and then the original update just lands late.
                    if (ticks > 5) {
                        level.setChunkForced(CHUNK.x, CHUNK.z, true);
                        next(Phase.WAIT_RELOAD);
                    }
                }
                case WAIT_RELOAD -> {
                    if (!isLoaded()) {
                        timeout("chunk never reloaded");
                    } else if (lampLit() && !level.hasNeighborSignal(LAMP)) {
                        next(Phase.VERIFY);
                    } else {
                        fail("chunk did not come back with a stale lamp, scenario not reproduced");
                    }
                }
                case VERIFY -> {
                    if (!lampLit()) {
                        done();
                        helper.succeed();
                    } else if (ticks > PHASE_TIMEOUT_TICKS) {
                        fail("lamp stuck on after the chunk reload, the lost neighbor update was never re-sent");
                    }
                }
            }
        }

        private void timeout(String reason) {
            if (ticks > PHASE_TIMEOUT_TICKS) {
                fail(reason);
            }
        }

        private void fail(String reason) {
            done();
            helper.fail(reason);
        }

        private void done() {
            active = null;
            level.setChunkForced(CHUNK.x, CHUNK.z, false);
        }

        private void next(Phase phase) {
            this.phase = phase;
            this.ticks = 0;
        }

        private boolean isLoaded() {
            return level.getChunkSource().getChunkNow(CHUNK.x, CHUNK.z) != null;
        }

        private boolean lampLit() {
            return level.getBlockState(LAMP).getValue(RedstoneLampBlock.LIT);
        }
    }
}
