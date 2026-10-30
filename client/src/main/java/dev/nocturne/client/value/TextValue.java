package dev.nocturne.client.value;

/** 长度受限的文本设置；{@code null} 一律归一为空串。 */
public final class TextValue extends Value<String> {

    private final int maxLength;

    public TextValue(String name, String defaultValue, int maxLength) {
        super(name, defaultValue);
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be positive");
        }
        this.maxLength = maxLength;
        set(defaultValue());
        normaliseDefault();
    }

    public int maxLength() {
        return maxLength;
    }

    @Override
    protected String coerce(String newValue) {
        if (newValue == null) {
            return "";
        }
        return newValue.length() > maxLength ? newValue.substring(0, maxLength) : newValue;
    }

    @Override
    public String display() {
        return get();
    }
}
