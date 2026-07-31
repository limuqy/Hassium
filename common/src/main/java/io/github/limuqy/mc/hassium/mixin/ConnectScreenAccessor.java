package io.github.limuqy.mc.hassium.mixin;

import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ConnectScreen.class)
public interface ConnectScreenAccessor {
    @Accessor("connection")
    Connection hassium$getConnection();
}
