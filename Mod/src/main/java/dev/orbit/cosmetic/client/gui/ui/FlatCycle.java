package dev.orbit.cosmetic.client.gui.ui;

import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** A flat button showing "Name: value" (or only the value, with an empty name) that steps through a list of values when clicked. */
public final class FlatCycle {
	private FlatCycle() {
	}

	public static <T> FlatButton of(int x, int y, int width, int height, Component name, List<T> values, T current, Function<T, Component> label,
		Function<T, Component> tooltip, Consumer<T> onChange) {
		Object[] value = {current};
		FlatButton button = new FlatButton(x, y, width, height, text(name, label.apply(current)), null, FlatButton.Style.SECONDARY, b -> {
			@SuppressWarnings("unchecked")
			T next = values.get((values.indexOf((T) value[0]) + 1) % values.size());
			value[0] = next;
			b.setMessage(text(name, label.apply(next)));
			if (tooltip != null) {
				b.setTooltip(Tooltip.create(tooltip.apply(next)));
			}
			onChange.accept(next);
		});
		if (tooltip != null) {
			button.setTooltip(Tooltip.create(tooltip.apply(current)));
		}
		return button;
	}

	public static FlatButton onOff(int x, int y, int width, int height, Component name, boolean current, Component tooltip, Consumer<Boolean> onChange) {
		FlatButton button = of(x, y, width, height, name, List.of(false, true), current, v -> v ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF, null, onChange);
		button.setTooltip(Tooltip.create(tooltip));
		return button;
	}

	/** "Name: value", or only the value without a name (for columns that have a header). */
	private static Component text(Component name, Component value) {
		if (name.getString().isEmpty()) {
			return value;
		}
		return Component.empty().append(name).append(": ").append(value);
	}
}
