package dev.epoch;

import dev.epoch.bridge.AutoRuntime;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Vanilla-style instance chooser. Selection prepares a target before freezing the world. */
final class VersionListScreen extends Screen {
  private List<AutoRuntime.Instance> instances = Collections.emptyList();
  private int offset;
  private int visibleRows;
  private String error = "";
  private String runtimeStatus = "Detecting local instances…";
  private long nextRefresh;
  private final Path gameDir = Minecraft.getInstance().gameDirectory.toPath();

  VersionListScreen() {
    super(Component.literal("Epoch — Switch version"));
  }

  @Override
  protected void init() {
    instances = AutoRuntime.instances(gameDir);
    runtimeStatus = AutoRuntime.statusText(gameDir);
    rebuild();
  }

  @Override
  public void tick() {
    if (System.currentTimeMillis() < nextRefresh) return;
    nextRefresh = System.currentTimeMillis() + 500;
    runtimeStatus = AutoRuntime.statusText(gameDir);
    List<AutoRuntime.Instance> updated = AutoRuntime.instances(gameDir);
    if (!signature(updated).equals(signature(instances))) {
      instances = updated;
      rebuild();
    }
  }

  private String signature(List<AutoRuntime.Instance> entries) {
    StringBuilder text = new StringBuilder();
    for (AutoRuntime.Instance entry : entries)
      text.append(entry.id).append('|').append(entry.label).append('|').append(entry.current)
          .append('|').append(entry.supported).append('|').append(entry.reason)
          .append('|').append(entry.warmupPhase).append('|').append(entry.warmupError).append('|').append(entry.warmupAvailable).append('\n');
    return text.toString();
  }

  private void rebuild() {
    clearWidgets();
    visibleRows = Math.max(1, (height - 154) / 27);
    offset = Math.max(0, Math.min(offset, Math.max(0, instances.size() - visibleRows)));
    int listWidth = Math.min(420, width - 36);
    for (int i = 0; i < visibleRows && offset + i < instances.size(); i++) {
      AutoRuntime.Instance instance = instances.get(offset + i);
      String label = instance.label + " | " + instance.version + " / Forge " + instance.forgeVersion;
      if (instance.current) label += " [Current]";
      else if (!instance.supported) label += " [Unavailable]";
      int selectWidth = listWidth - 86;
      Button button = Button.builder(Component.literal(font.plainSubstrByWidth(label, selectWidth - 12)), b -> select(instance))
          .bounds((width - listWidth) / 2, 66 + i * 27, selectWidth, 22).build();
      button.active = instance.supported && !instance.current;
      button.setTooltip(Tooltip.create(Component.literal(label + "\n" + instance.detail() + "\n" + instance.gameDir)));
      addRenderableWidget(button);
      Button warmup = Button.builder(Component.literal(instance.warmupLabel()), b -> warmup(instance))
          .bounds((width + listWidth) / 2 - 80, 66 + i * 27, 80, 22).build();
      warmup.active = instance.canWarmup();
      warmup.setTooltip(Tooltip.create(Component.literal(instance.detail())));
      addRenderableWidget(warmup);
    }
    int y = height - 70;
    Button previous = Button.builder(Component.literal("Previous"), b -> {
      offset -= visibleRows;
      rebuild();
    }).bounds(width / 2 - 154, y, 100, 20).build();
    previous.active = offset > 0;
    addRenderableWidget(previous);
    addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> {
      AutoRuntime.refresh(gameDir);
      instances = AutoRuntime.instances(gameDir);
      error = "";
      rebuild();
    }).bounds(width / 2 - 50, y, 100, 20).build());
    Button next = Button.builder(Component.literal("Next"), b -> {
      offset += visibleRows;
      rebuild();
    }).bounds(width / 2 + 54, y, 100, 20).build();
    next.active = offset + visibleRows < instances.size();
    addRenderableWidget(next);
    addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
        .bounds(width / 2 - 100, height - 25, 200, 20).build());
  }

  private void select(AutoRuntime.Instance instance) {
    try {
      AutoRuntime.select(gameDir, Epoch.VERSION, instance.id);
      onClose();
    } catch (Exception exception) {
      error = exception.getMessage() == null ? exception.toString() : exception.getMessage();
    }
  }

  private void warmup(AutoRuntime.Instance instance) {
    try {
      AutoRuntime.warmup(gameDir, Epoch.VERSION, instance.id);
      error = "";
    } catch (Exception exception) {
      error = exception.getMessage() == null ? exception.toString() : exception.getMessage();
    }
    instances = AutoRuntime.instances(gameDir);
    rebuild();
  }

  @Override
  public boolean mouseScrolled(double x, double y, double amount) {
    int next = Math.max(0, Math.min(offset - (int) Math.signum(amount), Math.max(0, instances.size() - visibleRows)));
    if (next != offset) {
      offset = next;
      rebuild();
      return true;
    }
    return super.mouseScrolled(x, y, amount);
  }

  @Override
  public boolean isPauseScreen() {
    return false;
  }

  @Override
  public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    renderBackground(graphics);
    graphics.drawCenteredString(font, title, width / 2, 15, 0xffffff);
    graphics.drawCenteredString(font, "Single player • shared region: X/Z -32..31, Y 0..255", width / 2, 32, 0xaaaaaa);
    graphics.drawCenteredString(font, "预热可并行准备多个版本，不切换当前版本", width / 2, 46, 0xaaaaaa);
    if (instances.isEmpty())
      graphics.drawCenteredString(font, "No instances found. Place Forge instances in this .minecraft directory.", width / 2, 76, 0xdddddd);
    String status = error.isEmpty() ? runtimeStatus : error;
    graphics.drawCenteredString(font, font.plainSubstrByWidth(status, width - 24), width / 2, height - 42,
        error.isEmpty() ? 0xaaaaaa : 0xff8888);
    super.render(graphics, mouseX, mouseY, partialTick);
  }
}
