package io.github.limuqy.mc.hassium.client;

import io.github.limuqy.mc.hassium.client.scenario.ScenarioEngine;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开发环境客户端冒烟测试门面：委托 {@link ScenarioEngine} 数据驱动执行。
 * <p>
 * 场景 = 步骤序列，由 JVM 属性 {@code hassium.smokeScenario=<name>} 选择
 * （未设置时默认 classic；{@code hassium.smokeTest.migrateTo} 非空则 migrate），
 * 内置 classic / migrate 两个场景文件，行为与旧硬编码状态机完全一致。
 * <p>
 * 启用方式（JVM 系统属性）：
 * <ul>
 *   <li>{@code -Dhassium.smokeTest=true} 开启</li>
 *   <li>{@code -Dhassium.smokeScenario=classic|migrate} 场景选择（可选）</li>
 *   <li>{@code -Dhassium.smokeTest.delayMs=6000} 每轮进服后等待毫秒（默认 10000；ROUND1 窗口=delayMs×2，ROUND2=max(3000,delayMs)）</li>
 *   <li>{@code -Dhassium.smokeTest.reconnectDelayMs=3000} 两轮间隔毫秒（默认 3000）</li>
 *   <li>{@code -Dhassium.smokeTest.joinTimeoutMs=120000} 未进服超时（默认 120s）</li>
 *   <li>{@code -Dhassium.smokeTest.host=127.0.0.1:25565} 重连目标地址</li>
 *   <li>{@code -Dhassium.smokeTest.moveSeconds=6} 进服后飞行移动秒数（0=不动）</li>
 *   <li>{@code -Dhassium.smokeTest.migrateTo=host:port} 迁移演练目标（隐式选择 migrate 场景）</li>
 *   <li>{@code -Dhassium.smokeTest.migrateImmediate=true} 走 NetworkCore.migrateToImmediate API 直调</li>
 *   <li>{@code -Dhassium.smokeTest.migrateMoveSeconds=3} 迁移触发后继续移动秒数</li>
 * </ul>
 * 退出码：0 两轮均通过；2 统计校验/迁移失败；3 进服超时；非 0 其它为运行错误。
 */
public final class ClientSmokeTest {

    private static final Logger LOGGER = LoggerFactory.getLogger("Hassium/SmokeTest");

    private ClientSmokeTest() {
    }

    public static boolean isEnabled() {
        return Boolean.parseBoolean(System.getProperty("hassium.smokeTest", "false"));
    }

    public static void initIfEnabled() {
        if (!isEnabled()) {
            return;
        }
        try {
            ScenarioEngine.init();
        } catch (Throwable t) {
            // 场景解析/加载失败：fail-fast 输出 FAIL marker 并按运行错误退出
            LOGGER.error("HassiumSmokeTest:FAIL scenario init failed", t);
            ScenarioEngine.abort("scenario init failed: " + t);
        }
    }

    /** 在客户端 tick 中驱动（MixinClientTick 注入点不变）。 */
    public static void onClientTick(Minecraft mc) {
        ScenarioEngine.onClientTick(mc);
    }
}
