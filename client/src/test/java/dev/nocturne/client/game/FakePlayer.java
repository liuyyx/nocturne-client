package dev.nocturne.client.game;

/** 替身本地玩家：暴露 {@code isDead} 字段与 {@code respawnPlayer()}，供 AutoRespawn 测试计数。 */
public final class FakePlayer {

    /** {@code Entity.isDead} 对应字段 */
    public boolean isDead;
    /** {@code respawnPlayer()} 被调用次数 */
    public int respawnCalls;

    /** 记录一次重生请求 */
    public void respawnPlayer() {
        respawnCalls++;
    }
}
