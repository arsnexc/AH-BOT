package dev.chunkfinder.mixin;

import dev.chunkfinder.FreecamModule;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Vanilla turns LocalPlayer directly, even when another entity is the camera. */
@Mixin(MouseHandler.class)
public abstract class MouseLookMixin {
    @Redirect(method = "turnPlayer", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void chunkfinder$turnDetachedCamera(LocalPlayer player, double yaw, double pitch) {
        if (!FreecamModule.turnCamera(player, yaw, pitch)) player.turn(yaw, pitch);
    }
}
