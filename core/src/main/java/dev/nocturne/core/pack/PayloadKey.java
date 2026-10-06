package dev.nocturne.core.pack;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 随 jar 分发的载荷所用密钥的派生点。
 *
 * <p><b>威胁模型（必须如实理解）</b>：密钥种子写死在代码里，因此它<em>不</em>构成对定向逆向的
 * 防护——任何能读到本类的人都能解出载荷。它挡的是「随手 {@code unzip} 一眼看到内嵌 ASM /
 * 直接用现成反编译工具批量扫资源」这类零成本静态检查，仅此而已。真正需要保密的材料不应内嵌
 * 在分发物里；若将来要换成会话密钥，应改为注入时随 attach 参数下发并同步重建载荷。
 *
 * <p>派生方式固定（SHA-256），这样构建期（dist 的 ASM 载荷打包任务）与运行期（agent 的
 * {@code EmbeddedAsmLoader}）不需要共享任何额外状态。
 */
public final class PayloadKey {

    /** 密钥种子；改动它等于让已分发的旧载荷全部失效（构建期与运行期必须一致）。 */
    private static final String ASM_SEED = "nocturne/asm-payload/v1|9f2b7c41a5d3e806";

    /** 工具类，禁止实例化。 */
    private PayloadKey() {
    }

    /**
     * 内嵌 ASM 载荷的 AES-256 密钥。
     *
     * @return 32 字节密钥（每次返回同一份新数组，调用方可以自由持有）
     */
    public static byte[] asmPayload() {
        return digest(ASM_SEED);
    }

    /** 把种子单向散列成 32 字节密钥（直接写 32 字节字面量容易抄错，派生更可审计）。 */
    private static byte[] digest(String seed) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(seed.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
