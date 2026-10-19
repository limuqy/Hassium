package io.github.limuqy.mc.hassium.mixin.shadow;

import net.minecraft.commands.Commands;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.world.flag.FeatureFlagSet;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.LayeredRegistryAccess;
import net.minecraft.core.Registry;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.server.ReloadableServerRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Constructs empty server resources without running datapack or gameplay reload listeners. */
@Mixin(ReloadableServerResources.class)
public interface ReloadableServerResourcesFactory {
    @Invoker("<init>")
#if MC_VER >= MC_26_3
    static ReloadableServerResources hassium$create(ReloadableServerRegistries.LoadResult loadingContext,
            FeatureFlagSet features, Commands.CommandSelection commands,
            List<Registry.PendingTags<?>> tags, PermissionSet permission,
            List<DataComponentInitializers.PendingComponents<?>> components) {
#else
    static ReloadableServerResources hassium$create(LayeredRegistryAccess<RegistryLayer> registries,
            HolderLookup.Provider lookup, FeatureFlagSet features, Commands.CommandSelection commands,
            List<Registry.PendingTags<?>> tags, PermissionSet permission,
            List<DataComponentInitializers.PendingComponents<?>> components) {
#endif
        throw new AssertionError();
    }
}
