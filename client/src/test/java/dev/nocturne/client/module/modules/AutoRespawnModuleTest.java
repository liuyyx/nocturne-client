package dev.nocturne.client.module.modules;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.FakePlayer;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link AutoRespawnModule} 的重生闩锁与冷却测试（M-133）。
 *
 * <p>旧实现每 tick 都调用 {@code respawnPlayer()}（20 次/秒），与服务端对重生包的频率限制叠加后
 * 行为与手动点击明显不同。这里用替身游戏类驱动真实模块代码，断言「同一次死亡只请求一次」。
 */
class AutoRespawnModuleTest {

    private ClassLoader savedTccl;

    @BeforeEach
    void setUp() {
        savedTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        TestSupport.resetClient();
        FakeMinecraft.reset();
    }

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
        Thread.currentThread().setContextClassLoader(savedTccl);
    }

    /** 接好替身游戏桥 */
    private static AutoRespawnModule armedModule(FakePlayer player) {
        FakeMinecraft.instance.player = player;
        NocturneClient.boot(null).setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));
        AutoRespawnModule module = new AutoRespawnModule();
        module.setEnabled(true);
        return module;
    }

    /** 把上次请求时间拨到指定的过去时刻（绕过真实等待，使冷却判定确定） */
    private static void setLastRequest(AutoRespawnModule module, long millisAgo) throws Exception {
        Field field = AutoRespawnModule.class.getDeclaredField("lastRequestMs");
        field.setAccessible(true);
        field.setLong(module, System.currentTimeMillis() - millisAgo);
    }

    /** 死亡后连续 tick 只允许请求一次重生 */
    @Test
    void latchPreventsRepeatedRespawnRequestsWithinOneDeath() {
        FakePlayer player = new FakePlayer();
        player.isDead = true;
        AutoRespawnModule module = armedModule(player);

        for (int i = 0; i < 20; i++) {
            module.onTick();
        }
        assertEquals(1, player.respawnCalls, "one death must produce exactly one respawn request");
    }

    /** 闩锁在玩家脱离死亡状态后复位，且冷却会拦住紧随其后的第二次死亡 */
    @Test
    void latchResetsAfterLeavingDeathAndCooldownGuardsTheNextDeath() throws Exception {
        FakePlayer player = new FakePlayer();
        player.isDead = true;
        AutoRespawnModule module = armedModule(player);

        module.onTick();
        assertEquals(1, player.respawnCalls);

        // 服务端已处理重生：闩锁复位
        player.isDead = false;
        module.onTick();
        assertEquals(1, player.respawnCalls);

        // 冷却未到：再次死亡不得立刻重发
        player.isDead = true;
        module.onTick();
        assertEquals(1, player.respawnCalls, "cooldown must suppress an immediate retry");

        // 冷却过后允许新的一次请求
        setLastRequest(module, 5000L);
        module.onTick();
        assertEquals(2, player.respawnCalls, "after the cooldown a new death is respawned");
    }

    /** 客户端/游戏桥缺失（主菜单、非游戏 JVM）时 tick 必须安全返回 */
    @Test
    void tickingWithoutAClientIsHarmless() {
        TestSupport.resetClient();
        AutoRespawnModule module = new AutoRespawnModule();
        module.setEnabled(true);
        module.onTick();
        module.onTick();
    }
}
