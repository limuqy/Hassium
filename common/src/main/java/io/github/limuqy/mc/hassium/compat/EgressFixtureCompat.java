package io.github.limuqy.mc.hassium.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.Level;

/** Resolve fixture entity types by registry across the 26.x constant-holder move. */
public final class EgressFixtureCompat {
    @SuppressWarnings("unchecked")
    public static Pig pig(Level level) {
        return new Pig((EntityType<? extends Pig>)BuiltInRegistries.ENTITY_TYPE.getValue(ResourceLocationCompat.create("minecraft", "pig")), level);
    }
    private EgressFixtureCompat() {}
}
