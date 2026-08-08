package io.github.limuqy.mc.hassium.mixin;

import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 服务端光照任务队列 accessor（影子端收敛判定用）。
 * <p>
 * ThreadedLevelLightEngine 的 pre/post-update 任务先积在 lightTasks，
 * 由 tryScheduleUpdate 驱动执行；hasLightWork() 只反映传播队列，
 * 任务未执行时可能误判收敛，必须同时检查 lightTasks 空。
 */
@Mixin(ThreadedLevelLightEngine.class)
public interface ThreadedLevelLightEngineAccessor {

    @Accessor("lightTasks")
    ObjectList<?> hassium$getLightTasks();
}
