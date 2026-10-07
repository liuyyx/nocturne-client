package dev.nocturne.client.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MethodCandidate} 的值语义测试：名字必填、描述符可空、相等性按 (名字, 描述符) 判定。
 *
 * <p>这几条不是形式主义：候选列表去重与「同命名空间配套描述符」的契约都建立在
 * 「名字 + 描述符」这一对值之上，equals/hashCode 写错会让去重偶发失效。
 */
class MethodCandidateTest {

    /** 名字与描述符都按构造入参暴露。 */
    @Test
    void exposesTheConstructorArguments() {
        MethodCandidate candidate = new MethodCandidate("A", "()Lave;");
        assertEquals("A", candidate.name());
        assertEquals("()Lave;", candidate.descriptor());
    }

    /** 描述符允许为 {@code null}（表示该命名空间没有记录描述符）。 */
    @Test
    void allowsNullDescriptor() {
        MethodCandidate candidate = new MethodCandidate("getInstance", null);
        assertEquals("getInstance", candidate.name());
        assertEquals(null, candidate.descriptor());
        assertEquals(new MethodCandidate("getInstance", null), candidate);
        assertEquals(new MethodCandidate("getInstance", null).hashCode(), candidate.hashCode());
    }

    /** {@code null} 名字在构造时即被拒绝，避免候选列表出现“无名可试”的条目。 */
    @Test
    void rejectsNullName() {
        assertThrows(NullPointerException.class, () -> new MethodCandidate(null, "()V"));
        assertThrows(NullPointerException.class, () -> new MethodCandidate(null, null));
    }

    /** 相等性：名字与描述符都相等才算同一条候选。 */
    @Test
    void equalityCoversNameAndDescriptor() {
        MethodCandidate candidate = new MethodCandidate("A", "()Lave;");
        assertEquals(candidate, new MethodCandidate("A", "()Lave;"));
        assertEquals(candidate.hashCode(), new MethodCandidate("A", "()Lave;").hashCode());
        assertFalse(candidate.equals(new MethodCandidate("A", null)));
        assertFalse(candidate.equals(new MethodCandidate("B", "()Lave;")));
        assertFalse(candidate.equals(null));
        assertNotEquals(new MethodCandidate("A", null), new MethodCandidate("A", "()V"));
    }

    /** toString 带出名字与描述符，便于诊断日志。 */
    @Test
    void toStringIncludesNameAndDescriptor() {
        assertTrue(new MethodCandidate("A", "()Lave;").toString().contains("()Lave;"));
        assertTrue(new MethodCandidate("A", null).toString().contains("A"));
    }
}
