package com.skydeps.autotacz.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.skydeps.autotacz.starter.UpdateApplier;
import com.skydeps.autotacz.starter.UpdateChecker;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * 启动前的枪包更新窗口。
 *
 * <p>交互设计上的几个决定：
 * <ul>
 *   <li><b>ESC 不关闭</b>：避免误按跳过更新。只能用明确按钮离开。</li>
 *   <li><b>网络异常时按钮变成「跳过」</b>：绝不把玩家卡在启动界面，
 *       检查失败也必须能进游戏。</li>
 *   <li><b>逐条勾选</b>：枪包动辄上百 MB，强制全量更新不可接受。</li>
 *   <li><b>渲染只读 volatile 快照</b>：检查在后台线程跑，界面从不阻塞等待网络。</li>
 * </ul>
 */
public final class StarterScreen extends Screen {

    private static final int ROW_HEIGHT = 18;
    private static final int LIST_TOP_MARGIN = 52;
    private static final int LIST_BOTTOM_RESERVED = 64;
    private static final int PANEL_WIDTH = 340;

    private final UpdateChecker checker;
    private final UpdateApplier applier;

    /** 用户取消勾选的 namespace，默认全部勾选，因此记录「排除项」而非「选中项」。 */
    private final Set<String> excluded = new HashSet<>();

    private int scrollOffset;
    private int panelLeft;
    private int panelTop;
    private int panelHeight;

    private Button primaryButton;
    private Button secondaryButton;

    /** 应用更新的后台任务，非 null 时界面处于「更新中」。 */
    @Nullable
    private CompletableFuture<List<UpdateApplier.ApplyResult>> applyTask;

    private volatile String applyProgress = "";
    private volatile boolean applying;

    @Nullable
    private List<UpdateApplier.ApplyResult> applyResults;

    /** 是否已因「无更新且 autoContinue 开启」自动放行，避免每帧重复调度。 */
    private boolean autoProceeded;

    public StarterScreen(UpdateChecker checker, UpdateApplier applier) {
        super(Component.translatable("autotacz.starter.title"));
        this.checker = checker;
        this.applier = applier;
    }

    @Override
    protected void init() {
        panelLeft = (width - PANEL_WIDTH) / 2;
        panelHeight = Math.min(height - 60, 240);
        panelTop = (height - panelHeight) / 2;

        int buttonY = panelTop + panelHeight + 12;
        int buttonWidth = 120;

        primaryButton = addRenderableWidget(Button.builder(
                        Component.translatable("autotacz.starter.enter"),
                        b -> onPrimary())
                .bounds(width / 2 - buttonWidth - 4, buttonY, buttonWidth, 20)
                .build());

        secondaryButton = addRenderableWidget(Button.builder(
                        Component.translatable("autotacz.starter.skip"),
                        b -> onSecondary())
                .bounds(width / 2 + 4, buttonY, buttonWidth, 20)
                .build());

        refreshButtons();
    }

    /**
     * 依据当前状态更新按钮文案与可用性。
     *
     * <p>每帧调用一次是廉价的（只设置字符串），换来的是界面永远与状态同步，
     * 不需要额外的状态变更通知机制。
     */
    private void refreshButtons() {
        if (primaryButton == null || secondaryButton == null) {
            return;
        }

        // 无更新且开启了 autoContinue：不弹空窗口，直接放行进游戏。
        // 放在这里而不是 init()，是因为检查是异步的——init 时状态通常还是
        // IDLE，只有刷新时才知道结果。autoProceeded 保证只调度一次。
        if (!autoProceeded && !applying && applyResults == null
                && checker.canProceedAutomatically()) {
            autoProceeded = true;
            // 不能在 render 过程中直接 setScreen：那会在遍历 renderables 时
            // 换掉整个界面。投递到下一 tick 执行。
            if (minecraft != null) {
                minecraft.execute(this::enterGame);
            }
            return;
        }
        if (autoProceeded) {
            return;
        }

        UpdateChecker.Status status = checker.status();

        switch (status) {
            case IDLE, SCANNING, FETCHING -> {
                primaryButton.setMessage(Component.translatable("autotacz.starter.checking"));
                primaryButton.active = false;
                secondaryButton.setMessage(Component.translatable("autotacz.starter.skip"));
                // 检查中允许跳过，这是「网络卡住」时的逃生口
                secondaryButton.active = true;
            }
            case DONE -> {
                int selected = selectedCandidates().size();
                if (selected > 0) {
                    primaryButton.setMessage(Component.translatable("autotacz.starter.update", selected));
                    primaryButton.active = !applying;
                    secondaryButton.setMessage(Component.translatable("autotacz.starter.enter"));
                    secondaryButton.active = !applying;
                } else {
                    primaryButton.setMessage(Component.translatable("autotacz.starter.enter"));
                    primaryButton.active = !applying;
                    secondaryButton.setMessage(Component.translatable("autotacz.starter.refresh"));
                    secondaryButton.active = !applying;
                }
            }
            case NETWORK_ERROR, FAILED -> {
                primaryButton.setMessage(Component.translatable("autotacz.starter.retry"));
                primaryButton.active = !applying;
                secondaryButton.setMessage(Component.translatable("autotacz.starter.skip.enter"));
                secondaryButton.active = true;
            }
        }
    }

    private void onPrimary() {
        UpdateChecker.Status status = checker.status();
        if (status == UpdateChecker.Status.DONE) {
            List<UpdateChecker.Candidate> selected = selectedCandidates();
            if (selected.isEmpty()) {
                enterGame();
            } else {
                startApply(selected);
            }
        } else if (status == UpdateChecker.Status.NETWORK_ERROR || status == UpdateChecker.Status.FAILED) {
            retry();
        }
    }

    private void onSecondary() {
        UpdateChecker.Status status = checker.status();
        if (status == UpdateChecker.Status.DONE && selectedCandidates().isEmpty()) {
            retry();
        } else {
            // 检查中跳过、异常跳过、或有更新但选择先进游戏 —— 都走这里
            skipAndEnter();
        }
    }

    private void skipAndEnter() {
        checker.markSkipped();
        enterGame();
    }

    private void enterGame() {
        if (minecraft != null) {
            minecraft.setScreen(new TitleScreen());
        }
    }

    private void retry() {
        excluded.clear();
        applyResults = null;
        scrollOffset = 0;
        checker.startAsync(this::refreshButtons);
    }

    /**
     * 在后台线程执行更新，界面只轮询进度。
     *
     * <p>用 CompletableFuture 而不是直接开线程，是为了能在完成时安全地
     * 把结果写回界面（{@code whenComplete} 中只赋值 volatile 字段，不做渲染）。
     */
    private void startApply(List<UpdateChecker.Candidate> selected) {
        applying = true;
        applyProgress = "准备中…";
        refreshButtons();

        applyTask = CompletableFuture.supplyAsync(() -> applier.applyAll(selected, (done, total, name) -> {
            applyProgress = done >= total ? "完成" : "正在更新 " + name + " (" + (done + 1) + "/" + total + ")";
        }));
        applyTask.whenComplete((results, error) -> {
            applying = false;
            applyResults = results;
            applyProgress = "";
        });
    }

    private List<UpdateChecker.Candidate> selectedCandidates() {
        return checker.candidates().stream()
                .filter(c -> !excluded.contains(c.local().namespace()))
                .toList();
    }

    // ------------------------------------------------------------ 渲染

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);

        refreshButtons();

        // 标题
        graphics.drawCenteredString(font, title, width / 2, panelTop - 24, 0xFFFFFF);

        UpdateChecker.Snapshot snapshot = checker.snapshot();

        // 状态行
        int statusColor = switch (snapshot.status()) {
            case NETWORK_ERROR, FAILED -> 0xFF5555;
            case DONE -> snapshot.candidates().isEmpty() ? 0x55FF55 : 0xFFAA00;
            default -> 0xAAAAAA;
        };
        graphics.drawCenteredString(font, Component.literal(snapshot.message()),
                width / 2, panelTop - 10, statusColor);

        drawPanel(graphics);

        if (applying) {
            graphics.drawCenteredString(font, Component.literal(applyProgress),
                    width / 2, panelTop + panelHeight / 2, 0xFFFFFF);
        } else if (applyResults != null) {
            drawResults(graphics);
        } else if (snapshot.status() == UpdateChecker.Status.DONE) {
            drawCandidateList(graphics, mouseX, mouseY, snapshot.candidates());
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void drawPanel(GuiGraphics graphics) {
        graphics.fill(panelLeft - 4, panelTop - 4,
                panelLeft + PANEL_WIDTH + 4, panelTop + panelHeight + 4, 0x66000000);
        graphics.fill(panelLeft, panelTop,
                panelLeft + PANEL_WIDTH, panelTop + panelHeight, 0x99000000);
    }

    private void drawResults(GuiGraphics graphics) {
        List<UpdateApplier.ApplyResult> results = applyResults;
        if (results == null) {
            return;
        }
        long ok = results.stream().filter(UpdateApplier.ApplyResult::ok).count();
        graphics.drawCenteredString(font,
                Component.literal("更新完成：" + ok + "/" + results.size() + " 成功")
                        .withStyle(ok == results.size() ? ChatFormatting.GREEN : ChatFormatting.YELLOW),
                width / 2, panelTop + 14, 0xFFFFFF);

        int y = panelTop + 34;
        for (UpdateApplier.ApplyResult result : results) {
            if (y > panelTop + panelHeight - 12) {
                break;
            }
            String text = result.ok()
                    ? "✓ " + result.namespace() + " → " + result.version()
                    : "✗ " + result.namespace() + ": " + result.error();
            graphics.drawString(font, text, panelLeft + 10, y,
                    result.ok() ? 0x55FF55 : 0xFF5555, false);
            y += 12;
        }
    }

    private void drawCandidateList(GuiGraphics graphics, int mouseX, int mouseY,
                                   List<UpdateChecker.Candidate> candidates) {
        if (candidates.isEmpty()) {
            graphics.drawCenteredString(font,
                    Component.translatable("autotacz.starter.none").withStyle(ChatFormatting.GRAY),
                    width / 2, panelTop + panelHeight / 2, 0xFFFFFF);
            return;
        }

        int visibleRows = visibleRowCount();
        int end = Math.min(candidates.size(), scrollOffset + visibleRows);
        for (int i = scrollOffset; i < end; i++) {
            UpdateChecker.Candidate candidate = candidates.get(i);
            int rowY = listTop() + (i - scrollOffset) * ROW_HEIGHT;
            boolean hovered = isRowHovered(mouseX, mouseY, rowY);
            boolean checked = !excluded.contains(candidate.local().namespace());

            if (hovered) {
                graphics.fill(panelLeft + 2, rowY, panelLeft + PANEL_WIDTH - 2,
                        rowY + ROW_HEIGHT - 2, 0x33FFFFFF);
            }

            // 复选框
            int boxX = panelLeft + 8;
            int boxY = rowY + 3;
            graphics.fill(boxX, boxY, boxX + 10, boxY + 10, 0xFF8B8B8B);
            graphics.fill(boxX + 1, boxY + 1, boxX + 9, boxY + 9, 0xFF1A1A1A);
            if (checked) {
                graphics.fill(boxX + 2, boxY + 2, boxX + 8, boxY + 8, 0xFF55FF55);
            }

            graphics.drawString(font, candidate.displayLine(), panelLeft + 24, rowY + 4,
                    checked ? 0xFFFFFF : 0x777777, false);
        }

        // 滚动提示
        if (candidates.size() > visibleRows) {
            String hint = (scrollOffset + 1) + "-" + end + " / " + candidates.size() + "  滚轮翻页";
            graphics.drawString(font, hint, panelLeft + 8,
                    panelTop + panelHeight - 12, 0x888888, false);
        }
    }

    private int listTop() {
        return panelTop + LIST_TOP_MARGIN - 32;
    }

    private int visibleRowCount() {
        int available = panelHeight - LIST_TOP_MARGIN + 32 - LIST_BOTTOM_RESERVED + 40;
        return Math.max(1, available / ROW_HEIGHT);
    }

    private boolean isRowHovered(int mouseX, int mouseY, int rowY) {
        return mouseX >= panelLeft + 2 && mouseX <= panelLeft + PANEL_WIDTH - 2
                && mouseY >= rowY && mouseY < rowY + ROW_HEIGHT - 2;
    }

    // ------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !applying && applyResults == null
                && checker.status() == UpdateChecker.Status.DONE) {
            List<UpdateChecker.Candidate> candidates = checker.candidates();
            int visibleRows = visibleRowCount();
            int end = Math.min(candidates.size(), scrollOffset + visibleRows);
            for (int i = scrollOffset; i < end; i++) {
                int rowY = listTop() + (i - scrollOffset) * ROW_HEIGHT;
                if (isRowHovered((int) mouseX, (int) mouseY, rowY)) {
                    String ns = candidates.get(i).local().namespace();
                    if (!excluded.remove(ns)) {
                        excluded.add(ns);
                    }
                    refreshButtons();
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int total = checker.candidates().size();
        int visibleRows = visibleRowCount();
        int maxOffset = Math.max(0, total - visibleRows);
        if (maxOffset == 0) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        // delta > 0 表示向上滚 -> 减少偏移
        scrollOffset = Mth.clamp(scrollOffset - (int) Math.signum(delta), 0, maxOffset);
        return true;
    }

    /** 不允许 ESC 关闭：必须明确选择进游戏或更新，避免误跳过。 */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}