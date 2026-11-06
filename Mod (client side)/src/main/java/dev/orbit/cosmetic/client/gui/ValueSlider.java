package dev.orbit.cosmetic.client.gui;

import dev.orbit.cosmetic.client.gui.ui.Theme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** A flat slider over a numeric range, snapped to {@code step}, with its label inside. */
final class ValueSlider extends AbstractWidget {
	private final double min;
	private final double max;
	private final double step;
	private final DoubleFunction<Component> label;
	private final DoubleConsumer onChange;
	private double value;

	ValueSlider(int x, int y, int width, double min, double max, double step, double current, DoubleFunction<Component> label, DoubleConsumer onChange) {
		super(x, y, width, 20, Component.empty());
		this.min = min;
		this.max = max;
		this.step = step;
		this.label = label;
		this.onChange = onChange;
		this.value = Mth.clamp(current, min, max);
		setMessage(label.apply(this.value));
	}

	double current() {
		return value;
	}

	private void setFromMouse(double mouseX) {
		double fraction = Mth.clamp((mouseX - getX() - 3) / (getWidth() - 6.0), 0, 1);
		double snapped = Mth.clamp(Math.round((min + fraction * (max - min)) / step) * step, min, max);
		if (snapped != value) {
			value = snapped;
			setMessage(label.apply(value));
			onChange.accept(value);
		}
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		setFromMouse(event.x());
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dx, double dy) {
		setFromMouse(event.x());
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(getX(), getY(), getRight(), getBottom(), isHoveredOrFocused() ? Theme.PANEL_HOVER : Theme.PANEL_2);
		graphics.outline(getX(), getY(), getWidth(), getHeight(), Theme.BORDER);
		double fraction = (value - min) / (max - min);
		int fillTo = getX() + 1 + (int) ((getWidth() - 2) * fraction);
		graphics.fill(getX() + 1, getBottom() - 3, fillTo, getBottom() - 1, Theme.BLUE);
		graphics.fill(fillTo - 1, getY() + 2, fillTo + 1, getBottom() - 1, Theme.TEXT);
		var font = Minecraft.getInstance().font;
		graphics.centeredText(font, Theme.clip(font, getMessage(), getWidth() - 6), getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2 - 1, active ? Theme.TEXT : Theme.FAINT);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, getMessage());
	}
}
