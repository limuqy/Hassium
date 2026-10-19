package io.github.limuqy.mc.hassium.mixin.shadow;

import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.world.flag.FeatureFlagSet;
#if MC_VER >= MC_1_21_2
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.server.RegistryLayer;
#endif
#if MC_VER >= MC_1_21_11
import net.minecraft.server.permissions.PermissionSet;
#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Constructs empty server resources without running datapack or gameplay reload listeners. */
@Mixin(ReloadableServerResources.class)
public interface ReloadableServerResourcesFactory {
#if MC_VER < MC_1_21_2
    @Invoker("<init>")
    static ReloadableServerResources hassium$create(RegistryAccess.Frozen registries,
            FeatureFlagSet features, Commands.CommandSelection commands, int permission) {
        throw new AssertionError();
    }
#elif MC_VER < MC_1_21_11
    @Invoker("<init>")
    static ReloadableServerResources hassium$create(LayeredRegistryAccess<RegistryLayer> registries,
            HolderLookup.Provider lookup, FeatureFlagSet features, Commands.CommandSelection commands,
            List<Registry.PendingTags<?>> tags, int permission) {
        throw new AssertionError();
    }
#else
    @Invoker("<init>")
    static ReloadableServerResources hassium$create(LayeredRegistryAccess<RegistryLayer> registries,
            HolderLookup.Provider lookup, FeatureFlagSet features, Commands.CommandSelection commands,
            List<Registry.PendingTags<?>> tags, PermissionSet permission) {
        throw new AssertionError();
    }
#endif
}
