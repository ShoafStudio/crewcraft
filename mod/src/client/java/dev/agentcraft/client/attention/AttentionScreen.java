package dev.agentcraft.client.attention;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.Protocol.Task;
import dev.agentcraft.client.foreman.Protocol.TaskStatus;
import dev.agentcraft.client.diff.ReviewKit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Read-only Mission Control board. Only the player sends the prepared Coordinator message. */
public final class AttentionScreen extends Screen {
    private static final String[] LANES = {"To do", "In review", "Blocked"};
    private static final int CARD_HEIGHT = 76;
    private final int[] scroll = new int[3];
    private String selected = "";
    private int activeLane, bodyScroll;
    private boolean detail;
    private int x, y, w, h, top, bottom, laneWidth;
    private List<Task> tasks = List.of();

    public AttentionScreen() { super(Component.literal("Attention board")); }
    @Override public boolean isPauseScreen() { return false; }

    private int lane(Task t) {
        return t.status() == TaskStatus.BLOCKED ? 2 : t.status() == TaskStatus.REVIEW ? 1 : 0;
    }
    private List<Task> laneTasks(int lane) { return tasks.stream().filter(t -> lane(t) == lane).toList(); }
    private Task selectedTask() { return tasks.stream().filter(t -> t.id().equals(selected)).findFirst().orElse(null); }
    private boolean compact() { return w < 470; }
    private int laneX(int lane) { return x + 12 + (compact() ? 0 : lane * (laneWidth + 8)); }
    private boolean inside(double mx, double my, int bx, int by, int bw, int bh) {
        return mx >= bx && mx < bx + bw && my >= by && my < by + bh;
    }
    private void refresh() {
        tasks = Foreman.state().tasks().values().stream()
            .filter(t -> "mission-control".equals(t.createdBy()) && "Needs attention".equals(t.summary()))
            .sorted(Comparator.comparingInt(Task::priority).reversed().thenComparingLong(Task::updatedAt).thenComparing(Task::id)).toList();
        if (selectedTask() == null) {
            selected = tasks.isEmpty() ? "" : tasks.getFirst().id();
            bodyScroll = 0;
            detail = false;
            Task t = selectedTask();
            if (t != null) activeLane = lane(t);
        }
    }
    private void text(GuiGraphicsExtractor g, String value, int tx, int ty, int max, int color) {
        g.text(font, TextUtil.ellipsize(font, value, Math.max(1, max)), tx, ty, color, false);
    }
    private void button(GuiGraphicsExtractor g, String label, int bx, int by, int bw, boolean primary, int mx, int my) {
        ReviewKit.button(g, font, TextUtil.ellipsize(font, label, bw - 12), bx, by, bw,
            primary ? ReviewKit.ButtonKind.PRIMARY : ReviewKit.ButtonKind.NORMAL,
            inside(mx, my, bx, by, bw, 20), false, false);
    }
    private void scrollbar(GuiGraphicsExtractor g, int bx, int by, int height, int offset, int total, int visible) {
        if (total <= visible || height <= 0) return;
        int thumb = Math.min(height, Math.max(12, height * visible / total));
        g.fill(bx, by, bx + 2, by + height, 0xFFE0D7C7);
        int pos = by + (height - thumb) * offset / Math.max(1, total - visible);
        g.fill(bx, pos, bx + 2, pos + thumb, ReviewKit.muted());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        refresh();
        w = Math.min(820, width - 16); h = Math.min(470, height - 16);
        x = (width - w) / 2; y = (height - h) / 2;
        top = y + 78; bottom = y + h - 38;
        laneWidth = compact() ? w - 24 : (w - 40) / 3;
        Panels.panel(g, x, y, w, h);
        ReviewKit.bold(g, font, "Attention board", x + 12, y + 12, ReviewKit.ink());
        button(g, "Close", x + w - 58, y + 8, 46, false, mx, my);
        var note = Foreman.state().memory().get("shared/attention");
        String status = Foreman.state().isStale() ? "Offline - last known board"
            : note == null ? "Mission Control not connected"
            : note.title().contains("unavailable") ? "Mission Control unavailable - board may be outdated"
            : tasks.size() + " pending  /  Configured coordinator  /  Syncs every minute";
        text(g, status, x + 12, y + 30, w - 24, ReviewKit.muted());
        Panels.divider(g, x + 12, y + 44, w - 24);
        if (detail && selectedTask() != null) renderDetail(g, mx, my);
        else {
            for (int i = 0; i < 3; i++) {
                int bx = compact() ? x + 12 + i * ((w - 24) / 3) : laneX(i);
                int bw = compact() ? (w - 24) / 3 - 3 : laneWidth;
                if (compact()) button(g, LANES[i] + " " + laneTasks(i).size(), bx, y + 51, bw, i == activeLane, mx, my);
                else {
                    ReviewKit.bold(g, font, LANES[i], bx + 6, y + 56, ReviewKit.ink());
                    String count = String.valueOf(laneTasks(i).size());
                    g.text(font, count, bx + bw - font.width(count) - 6, y + 56, ReviewKit.muted(), false);
                }
            }
            for (int i = 0; i < 3; i++) if (!compact() || i == activeLane) renderLane(g, i, mx, my);
            text(g, "Arrows select  /  Enter opens  /  J chat  /  Esc closes", x + 12, bottom + 17, w - 24, ReviewKit.muted());
        }
        super.extractRenderState(g, mx, my, delta);
    }

    private void renderLane(GuiGraphicsExtractor g, int column, int mx, int my) {
        int bx = laneX(column), available = Math.max(1, bottom - top);
        var list = laneTasks(column);
        int visible = Math.max(1, available / CARD_HEIGHT);
        scroll[column] = Math.clamp(scroll[column], 0, Math.max(0, list.size() - visible));
        g.fill(bx, top, bx + laneWidth, bottom, 0xFFE9E2D6);
        g.enableScissor(bx, top, bx + laneWidth, bottom);
        if (list.isEmpty()) {
            text(g, "Nothing here", bx + 10, top + 15, laneWidth - 20, ReviewKit.muted());
        }
        for (int i = scroll[column]; i < Math.min(list.size(), scroll[column] + visible + 1); i++) {
            Task t = list.get(i);
            int cy = top + (i - scroll[column]) * CARD_HEIGHT + 4;
            boolean chosen = selected.equals(t.id());
            boolean hover = inside(mx, my, bx + 4, cy, laneWidth - 10, CARD_HEIGHT - 6);
            g.fill(bx + 4, cy, bx + laneWidth - 6, cy + CARD_HEIGHT - 6, chosen ? 0xFFDFD2BC : hover ? 0xFFFDF8EF : 0xFFF5F0E7);
            if (chosen) g.fill(bx + 4, cy, bx + 7, cy + CARD_HEIGHT - 6, 0xFF97674E);
            text(g, "P" + (3 - t.priority()) + (t.repoId() != null ? "  /  PR" : ""), bx + 12, cy + 7, laneWidth - 26, ReviewKit.muted());
            var lines = font.split(Component.literal(t.title()), Math.max(20, laneWidth - 28));
            for (int j = 0; j < Math.min(3, lines.size()); j++) {
                if (j == 2 && lines.size() > 3) {
                    // Reserve an explicit continuation marker rather than silently truncating the title.
                    g.text(font, lines.get(j), bx + 12, cy + 23 + j * 11, ReviewKit.ink(), false);
                    g.fill(bx + laneWidth - 30, cy + 45, bx + laneWidth - 7, cy + 56, chosen ? 0xFFDFD2BC : hover ? 0xFFFDF8EF : 0xFFF5F0E7);
                    g.text(font, "...", bx + laneWidth - 26, cy + 45, ReviewKit.ink(), false);
                } else g.text(font, lines.get(j), bx + 12, cy + 23 + j * 11, ReviewKit.ink(), false);
            }
        }
        g.disableScissor();
        scrollbar(g, bx + laneWidth - 3, top + 3, available - 6, scroll[column], list.size(), visible);
    }

    private void renderDetail(GuiGraphicsExtractor g, int mx, int my) {
        Task t = selectedTask();
        button(g, "< Board", x + 12, y + 51, 72, false, mx, my);
        text(g, LANES[lane(t)] + "  /  P" + (3 - t.priority()), x + 94, y + 57, w - 110, ReviewKit.muted());
        int bodyWidth = Math.min(540, w - 48), bx = x + (w - bodyWidth) / 2;
        var lines = new ArrayList<FormattedCharSequence>();
        lines.addAll(font.split(Component.literal(t.title()).withStyle(net.minecraft.ChatFormatting.BOLD), bodyWidth - 12));
        lines.add(Component.literal(" ").getVisualOrderText());
        String description = t.description() == null || t.description().isBlank() ? "No description provided." : t.description();
        for (String paragraph : description.split("\n", -1)) {
            lines.addAll(font.split(Component.literal(paragraph.isEmpty() ? " " : paragraph), bodyWidth - 12));
        }
        int visible = Math.max(1, (bottom - top - 8) / 13);
        bodyScroll = Math.clamp(bodyScroll, 0, Math.max(0, lines.size() - visible));
        g.enableScissor(bx, top, bx + bodyWidth, bottom);
        for (int i = bodyScroll; i < Math.min(lines.size(), bodyScroll + visible); i++)
            g.text(font, lines.get(i), bx + 2, top + 4 + (i - bodyScroll) * 13, ReviewKit.ink(), false);
        g.disableScissor();
        scrollbar(g, bx + bodyWidth - 2, top + 4, bottom - top - 8, bodyScroll, lines.size(), visible);
        button(g, "Ask coordinator", x + 12, bottom + 8, 112, true, mx, my);
        if (hasDiff(t)) button(g, "PR diff", x + 132, bottom + 8, 68, false, mx, my);
        text(g, hasDiff(t) ? "J chat / D diff" : "J prepares a message", x + (hasDiff(t) ? 212 : 136), bottom + 14, w - (hasDiff(t) ? 224 : 148), ReviewKit.muted());
    }
    private Task actionTask() {
        Task t = selectedTask();
        return t != null && (detail || lane(t) == activeLane) ? t : null;
    }
    private boolean hasDiff(Task t) { return t != null && t.repoId() != null && t.worktree() != null; }
    private void openDiff() {
        Task t = actionTask();
        if (hasDiff(t)) dev.agentcraft.client.decisions.DiffLink.open(t.repoId(), t.worktree(), null, this);
    }
    private void talk() {
        Task t = actionTask();
        String id = t == null ? "" : t.id().startsWith("mc-") ? t.id().substring(3) : t.id();
        net.minecraft.client.Minecraft.getInstance().gui.setScreen(dev.agentcraft.client.console.ConsoleScreen.withContext("all",
            t == null ? "" : "Let's review Mission Control task " + id + ": " + t.title() + ". "));
    }
    @Override public boolean mouseClicked(MouseButtonEvent e, boolean twice) {
        if (inside(e.x(), e.y(), x + w - 58, y + 8, 46, 20)) { onClose(); return true; }
        if (detail) {
            if (inside(e.x(), e.y(), x + 12, y + 51, 72, 20)) { detail = false; return true; }
            if (inside(e.x(), e.y(), x + 12, bottom + 8, 112, 20)) { talk(); return true; }
            if (hasDiff(selectedTask()) && inside(e.x(), e.y(), x + 132, bottom + 8, 68, 20)) { openDiff(); return true; }
        } else for (int i = 0; i < 3; i++) {
            if (compact() && inside(e.x(), e.y(), x + 12 + i * ((w - 24) / 3), y + 51, (w - 24) / 3 - 3, 20)) {
                activeLane = i;
                var list = laneTasks(i);
                if (!list.isEmpty()) selected = list.get(Math.min(scroll[i], list.size() - 1)).id();
                return true;
            }
            if ((!compact() || i == activeLane) && inside(e.x(), e.y(), laneX(i) + 4, top, laneWidth - 10, bottom - top)) {
                int row = (int)(e.y() - top) / CARD_HEIGHT;
                int within = (int)(e.y() - top) % CARD_HEIGHT;
                var list = laneTasks(i);
                int index = scroll[i] + row;
                if (within >= 4 && within < CARD_HEIGHT - 2 && index < list.size()) {
                    selected = list.get(index).id(); activeLane = i; bodyScroll = 0; detail = true;
                }
                return true;
            }
        }
        return super.mouseClicked(e, twice);
    }
    @Override public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (!inside(mx, my, x, top, w, bottom - top)) return super.mouseScrolled(mx, my, sx, sy);
        int step = Math.round((float)sy);
        if (detail) bodyScroll -= step * 3;
        else for (int i = 0; i < 3; i++) if ((!compact() || i == activeLane) && inside(mx, my, laneX(i), top, laneWidth, bottom - top)) scroll[i] -= step;
        return true;
    }
    @Override public boolean keyPressed(KeyEvent e) {
        if (e.key() == InputConstants.KEY_J) { talk(); return true; }
        if (e.key() == InputConstants.KEY_D) { openDiff(); return true; }
        if (detail && (e.key() == InputConstants.KEY_ESCAPE || e.key() == InputConstants.KEY_BACKSPACE)) { detail = false; return true; }
        if (!detail && e.key() == InputConstants.KEY_RETURN && actionTask() != null) { detail = true; bodyScroll = 0; return true; }
        if (e.key() == InputConstants.KEY_RIGHT || e.key() == InputConstants.KEY_LEFT || e.key() == InputConstants.KEY_DOWN || e.key() == InputConstants.KEY_UP) {
            if (detail) bodyScroll += e.key() == InputConstants.KEY_UP ? -3 : e.key() == InputConstants.KEY_DOWN ? 3 : 0;
            else {
                if (e.key() == InputConstants.KEY_RIGHT || e.key() == InputConstants.KEY_LEFT) activeLane = Math.clamp(activeLane + (e.key() == InputConstants.KEY_RIGHT ? 1 : -1), 0, 2);
                var list = laneTasks(activeLane);
                if (!list.isEmpty()) {
                    int index = list.indexOf(selectedTask());
                    index = index < 0 ? 0 : Math.clamp(index + (e.key() == InputConstants.KEY_UP ? -1 : e.key() == InputConstants.KEY_DOWN ? 1 : 0), 0, list.size() - 1);
                    selected = list.get(index).id();
                    int visible = Math.max(1, (bottom - top) / CARD_HEIGHT);
                    scroll[activeLane] = Math.clamp(scroll[activeLane], Math.max(0, index - visible + 1), index);
                }
            }
            return true;
        }
        return super.keyPressed(e);
    }
}
