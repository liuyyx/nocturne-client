package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.action.ActionApi;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 换槽只发一种包。
 *
 * <p>回归：Silent 先 {@code selectSlot(slot,true)} 再 {@code selectSlot(slot,false)}，每 tick 发两个
 * ServerboundSetCarriedItem，Silent 与 Normal 行为完全一致。
 */
class ScaffoldSwapTest {

    private static ActionApi recorder(final List<String> calls) {
        return (ActionApi) Proxy.newProxyInstance(ScaffoldSwapTest.class.getClassLoader(),
                new Class<?>[]{ActionApi.class}, (proxy, method, args) -> {
                    if ("selectSlot".equals(method.getName())) {
                        calls.add("selectSlot(" + args[0] + "," + args[1] + ")");
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) {
                        return true;
                    }
                    if (type == int.class) {
                        return 0;
                    }
                    if (type == float.class) {
                        return 0.0f;
                    }
                    if (type == double.class) {
                        return 0.0;
                    }
                    if (type == long.class) {
                        return 0L;
                    }
                    return null;
                });
    }

    @Test
    void silentSendsExactlyOneSilentPacket() {
        List<String> calls = new ArrayList<String>();
        ScaffoldModule.applySwap(recorder(calls), true, 3);
        assertEquals(Arrays.asList("selectSlot(3,true)"), calls);
    }

    @Test
    void normalSendsExactlyOneLocalSwitch() {
        List<String> calls = new ArrayList<String>();
        ScaffoldModule.applySwap(recorder(calls), false, 3);
        assertEquals(Arrays.asList("selectSlot(3,false)"), calls);
    }
}
