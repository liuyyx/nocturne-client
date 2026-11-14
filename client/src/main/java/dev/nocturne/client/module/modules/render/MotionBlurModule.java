package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/**
 * 整帧运动模糊的参数，由帧后处理消费者读取；自身不捕获或绘制画面。
 *
 * <p>上游降级：仅混合上一帧，Samples 作为历史帧权重的缩放系数，并非实际采样次数；
 * 不支持方向采样，故不提供 Algorithm。缺少深度缓冲和刷新率补偿，故不提供 Depth Blur
 * 与 Refresh Rate Scaling。固定管线或不支持纹理/帧回读时无法生效。
 */
public final class MotionBlurModule extends GameModule {
    private final NumberValue strength = add(new NumberValue("Strength", 1.0, 0.0, 5.0, 0.05));
    private final NumberValue samples = add(new NumberValue("Samples", 100.0, 4.0, 256.0, 4.0));
    private final BooleanValue thirdPerson = add(new BooleanValue("Third Person", true));

    public MotionBlurModule() {
        super("Motion Blur", Category.RENDER);
    }

    /** 历史帧混合比例，默认 0.13，结果限制在 0 到 1。 */
    public float historyWeight() {
        return (float) Math.min(1.0, strength.get() * 0.13 * samples.get() / 100.0);
    }

    /** 是否同时允许第三人称画面使用运动模糊；第一人称始终允许。 */
    public boolean thirdPerson() {
        return thirdPerson.get();
    }
}
