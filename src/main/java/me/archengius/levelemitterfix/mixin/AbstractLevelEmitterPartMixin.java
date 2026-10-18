package me.archengius.levelemitterfix.mixin;

import appeng.api.parts.IPartItem;
import appeng.hooks.ticking.TickHandler;
import appeng.parts.automation.AbstractLevelEmitterPart;
import appeng.parts.automation.UpgradeablePart;
import appeng.util.Platform;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractLevelEmitterPart.class, remap = false)
public abstract class AbstractLevelEmitterPartMixin extends UpgradeablePart {
    @Unique
    private static final String levelemitterfix$NEIGHBOR_UPDATE_PENDING_TAG = "levelemitterfix:pendingNeighborUpdate";

    // True if neighbor update was queued but not processed yet
    @Unique
    private boolean levelemitterfix$neighborUpdatePending;
    // Set to true when the emitter was saved with a neighbor update still pending
    @Unique
    private boolean levelemitterfix$shouldResendNeighborUpdate;
    @Unique
    private int levelemitterfix$neighborUpdateCounter;

    public AbstractLevelEmitterPartMixin(IPartItem<?> partItem) {
        super(partItem);
    }

    @Inject(method = "updateState", at = @At("HEAD"))
    private void levelemitterfix$resendAfterLoad(CallbackInfo ci) {
        // Resend the neighbor update if we were saved with it pending
        if (levelemitterfix$shouldResendNeighborUpdate && !isClientSide()) {
            levelemitterfix$shouldResendNeighborUpdate = false;
            var be = getHost().getBlockEntity();
            Platform.notifyBlocksOfNeighbors(be.getLevel(), be.getBlockPos());
            Platform.notifyBlocksOfNeighbors(be.getLevel(), be.getBlockPos().relative(getSide()));
            levelemitterfix$setNeighborUpdatePending();
        }
    }

    @Inject(method = "updateState", at = @At(value = "INVOKE", target = "Lappeng/util/Platform;notifyBlocksOfNeighbors(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V", ordinal = 1, shift = At.Shift.AFTER))
    private void levelemitterfix$afterNotify(CallbackInfo ci) {
        levelemitterfix$setNeighborUpdatePending();
    }

    // Marks the neighbor update as pending and increment update counter. Once the update is delivered, resets the pending flag
    @Unique
    private void levelemitterfix$setNeighborUpdatePending() {
        var be = getHost().getBlockEntity();
        if (be.getLevel() == null || be.getLevel().isClientSide()) {
            return;
        }
        levelemitterfix$neighborUpdatePending = true;
        int savedCounter = ++levelemitterfix$neighborUpdateCounter;
        BlockPos self = be.getBlockPos();
        BlockPos target = self.relative(getSide());
        TickHandler.instance().addCallable(be.getLevel(), level -> {
            if (levelemitterfix$neighborUpdateCounter == savedCounter && level.hasChunkAt(self) && level.hasChunkAt(target)) {
                levelemitterfix$neighborUpdatePending = false;
            }
        });
    }

    @Inject(method = "writeToNBT", at = @At("TAIL"))
    private void levelemitterfix$write(CompoundTag data, CallbackInfo ci) {
        if (levelemitterfix$neighborUpdatePending || levelemitterfix$shouldResendNeighborUpdate) {
            data.putBoolean(levelemitterfix$NEIGHBOR_UPDATE_PENDING_TAG, true);
        }
    }

    @Inject(method = "readFromNBT", at = @At("TAIL"))
    private void levelemitterfix$read(CompoundTag data, CallbackInfo ci) {
        levelemitterfix$shouldResendNeighborUpdate = data.getBoolean(levelemitterfix$NEIGHBOR_UPDATE_PENDING_TAG);
    }
}
