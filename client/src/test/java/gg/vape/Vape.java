package gg.vape;

import gg.vape.sync.SyncThread;

/** 测试替身：仅用于验证 NocturneRuntime 的反射式 vendor 关闭路径。 */
public final class Vape {
    public static final Vape INSTANCE = new Vape();

    private Vape() {
    }

    public SyncThread getSyncThread() {
        return SyncThread.INSTANCE;
    }
}
