package dev.nocturne.ui;

import dev.nocturne.ui.gl.InputSource;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link InputSource} 的测试替身：用可写字段模拟指针与按键状态，从而在不依赖 LWJGL2/GLFW 的
 * 情况下驱动 {@link dev.nocturne.ui.gl.GuiOverlay} 的完整派发路径。
 */
public final class FakeInput implements InputSource {

    /** 当前被按下的键（AWT VK 码） */
    public final Set<Integer> keys = new HashSet<Integer>();
    /** 当前被按下的鼠标键（0 左键 / 1 右键） */
    public final Set<Integer> buttons = new HashSet<Integer>();
    /** 指针位置（GUI 坐标） */
    public double x;
    /** 指针位置（GUI 坐标） */
    public double y;
    /** 下一次 {@link #scrollDelta()} 返回的增量；取出后清零 */
    public double scroll;
    /**
     * 当前生效的指针捕获状态。
     *
     * <p>初始值即「游戏自己维护的状态」：主菜单里应为 {@code false}（光标可见），
     * 游戏中应为 {@code true}（视角捕获）。{@link #setPointerGrabbed(boolean)} 会改写它，
     * 因此断言它可以区分「叠加层把指针交还给 GUI」与「叠加层无条件捕获了指针」。
     */
    public boolean pointerGrabbed;
    /** {@link #setPointerGrabbed} 被调用的次数 */
    public int pointerGrabCalls;

    @Override
    public double mouseX() {
        return x;
    }

    @Override
    public double mouseY() {
        return y;
    }

    @Override
    public boolean mouseDown(int button) {
        return buttons.contains(button);
    }

    @Override
    public double scrollDelta() {
        double value = scroll;
        scroll = 0d;
        return value;
    }

    @Override
    public boolean keyDown(int key) {
        return keys.contains(key);
    }

    @Override
    public void setPointerGrabbed(boolean grabbed) {
        pointerGrabCalls++;
        pointerGrabbed = grabbed;
    }

    @Override
    public boolean isPointerGrabbed() {
        return pointerGrabbed;
    }

    @Override
    public String describe() {
        return "fake";
    }
}
