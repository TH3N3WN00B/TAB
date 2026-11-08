package me.neznamy.tab.shared.chat.component;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.neznamy.tab.shared.chat.TabStyle;
import me.neznamy.tab.shared.chat.EnumChatFormat;
import me.neznamy.tab.shared.chat.TabTextColor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A component of "text" type that contains text to display.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TabTextComponent extends TabComponent {

    @NotNull
    protected String text;

    /**
     * Constructs new instance using given text and extra components.
     *
     * @param   text
     *          Component text
     * @param   extra
     *          Extra components
     */
    public TabTextComponent(@NotNull String text, List<TabComponent> extra) {
        this.text = text;
        super.extra = extra;
    }

    /**
     * Constructs a new instance which clones existing component.
     *
     * @param   component
     *          Component to clone
     */
    public TabTextComponent(@NotNull TabTextComponent component) {
        text = component.text;
        modifier = new TabStyle(component.modifier);
    }

    /**
     * Constructs new instance with given text and color.
     *
     * @param   text
     *          Component text
     * @param   color
     *          Text color
     */
    public TabTextComponent(@NotNull String text, @Nullable TabTextColor color) {
        this.text = text;
        modifier.setColor(color);
    }

    @Override
    @NotNull
    public String toLegacyText() {
        StringBuilder builder = new StringBuilder();
        append(builder, new LegacyState(), new TabStyle());
        return builder.toString();
    }

    /**
     * Appends text to string builder, might also add color and magic codes if they are different
     * from the previous component in the chain. If codes of this component alone would not result
     * in its effective style (style inherited from parent components merged with its own style),
     * for example because a magic code of previous sibling would remain active, the full formatting is added instead.
     *
     * @param   builder
     *          builder to append text to
     * @param   state
     *          formatting written so far, updated by this method
     * @param   parentStyle
     *          effective style of parent component
     */
    private void append(@NotNull StringBuilder builder, @NotNull LegacyState state, @NotNull TabStyle parentStyle) {
        TabStyle style = getEffectiveStyle(parentStyle);
        String formatting = getFormatting(modifier);
        boolean changed = !formatting.equals(state.formatting);
        TabStyle active = state.active;
        if (changed) {
            // Simulate how the client applies the codes
            active = modifier.getColor() == null ? new TabStyle(active) : new TabStyle(); // Color code resets magic codes
            if (modifier.getColor() != null) active.setColor(modifier.getColor());
            if (Boolean.TRUE.equals(modifier.getBold())) active.setBold(true);
            if (Boolean.TRUE.equals(modifier.getItalic())) active.setItalic(true);
            if (Boolean.TRUE.equals(modifier.getObfuscated())) active.setObfuscated(true);
            if (Boolean.TRUE.equals(modifier.getStrikethrough())) active.setStrikethrough(true);
            if (Boolean.TRUE.equals(modifier.getUnderlined())) active.setUnderlined(true);
        }
        if (getLegacyColorChar(active) == getLegacyColorChar(style) && active.getMagicCodes().equals(style.getMagicCodes())) {
            if (changed) builder.append(formatting);
            state.active = active;
        } else {
            // Formatting of previous component would remain active (or own codes are not enough), write the full formatting
            builder.append(style.getColor() == null ? "§r" + style.getMagicCodes() : getFormatting(style));
            state.active = new TabStyle(style);
        }
        state.formatting = formatting;
        builder.append(text);
        for (TabComponent component : getExtra()) {
            if (component instanceof TabTextComponent) {
                ((TabTextComponent) component).append(builder, state, style);
            }
        }
    }

    /**
     * Returns effective style of this component, which is parent's style overridden by
     * color and magic codes defined in this component.
     *
     * @param   parentStyle
     *          Effective style of parent component
     * @return  Effective style of this component
     */
    @NotNull
    private TabStyle getEffectiveStyle(@NotNull TabStyle parentStyle) {
        TabStyle style = new TabStyle(parentStyle);
        if (modifier.getColor() != null) style.setColor(modifier.getColor());
        if (modifier.getBold() != null) style.setBold(modifier.getBold());
        if (modifier.getItalic() != null) style.setItalic(modifier.getItalic());
        if (modifier.getUnderlined() != null) style.setUnderlined(modifier.getUnderlined());
        if (modifier.getStrikethrough() != null) style.setStrikethrough(modifier.getStrikethrough());
        if (modifier.getObfuscated() != null) style.setObfuscated(modifier.getObfuscated());
        return style;
    }

    /**
     * Returns legacy color character of given style. Both white and no color
     * are represented by {@code r}, consistently with {@link #getFormatting(TabStyle)}.
     *
     * @param   style
     *          Style to get color character of
     * @return  Legacy color character of given style
     */
    private static char getLegacyColorChar(@NotNull TabStyle style) {
        if (style.getColor() == null || style.getColor().getLegacyColor() == EnumChatFormat.WHITE) return 'r';
        return style.getColor().getLegacyColor().getCharacter();
    }

    /**
     * Returns colors and magic codes of given style
     *
     * @param   style
     *          Style to get formatting of
     * @return  used colors and magic codes
     */
    private static @NotNull String getFormatting(@NotNull TabStyle style) {
        StringBuilder builder = new StringBuilder();
        if (style.getColor() != null) {
            builder.append("§");
            if (style.getColor().getLegacyColor() == EnumChatFormat.WHITE) {
                //preventing unwanted &r → &f conversion and stopping the <1.13 client bug fix from working
                builder.append("r");
            } else {
                builder.append(style.getColor().getLegacyColor().getCharacter());
            }
        }
        builder.append(style.getMagicCodes());
        return builder.toString();
    }

    /**
     * Formatting state of legacy text during conversion.
     */
    private static class LegacyState {

        /** Colors and magic codes of the previous component in the chain */
        @NotNull
        private String formatting = "";

        /** Color and magic codes currently active in the written text */
        @NotNull
        private TabStyle active = new TabStyle();
    }
}
