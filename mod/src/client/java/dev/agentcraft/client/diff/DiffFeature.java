package dev.agentcraft.client.diff;

import com.google.gson.JsonObject;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.entity.ModBlockEntities;
import dev.agentcraft.client.decisions.DiffLink;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.Repo;
import dev.agentcraft.client.foreman.Protocol.Worktree;
import dev.agentcraft.client.world.StationInteractions;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import org.jspecify.annotations.Nullable;

/**
 * Diff / merge review (Phase 3, diff specialist).
 *
 * <ul>
 *   <li>{@link DiffScreen}: the review screen (structured diff from {@code Foreman.requestDiff},
 *       Merge / Request changes / Reject for merge decisions, copy path).</li>
 *   <li>{@link MergeStationRenderer}: a review card on each merge station of a row (the k-th block
 *       from the left shows the k-th waiting merge; the first one carries the count).</li>
 *   <li>Right-click a merge station: review the merge its card shows (the oldest open merge on the
 *       first station of a row).</li>
 *   <li>QA: {@code dev.screen {open:"diff"}} (oldest open merge decision, else an active worktree),
 *       {@code dev.diff {decisionId? | repoId + worktree, file?, scroll?, wrap?, syntax?, mode?}}
 *       opens / drives it and returns its state, {@code dev.diff.state}, and
 *       {@code dev.review.guiScale {scale}} to check GUI scales 2/3/4.</li>
 * </ul>
 *
 * Other features open it with {@link #open(DiffScreen.Target)} / {@link #forDecision(Decision)}
 * (e.g. the decision podium for a merge decision).
 */
public final class DiffFeature {
	private DiffFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.MERGE_STATION, ctx -> new MergeStationRenderer());
		DevBridge.registerScreen("diff", mc -> new DiffScreen(defaultTarget()));
		// D on a merge decision and /diff review exactly that decision / worktree (not the oldest open merge)
		DiffLink.setOpener((repoId, worktree, decision, parent) -> new DiffScreen(decision != null
			? new DiffScreen.Target(decision.id(), repoId, worktree) : forWorktree(repoId, worktree)));
		StationInteractions.onUse(ModBlocks.MERGE_STATION, (player, pos, state, be) -> {
			List<Decision> queue = MergeStationRenderer.queue();
			int k = MergeStationRenderer.rowIndex(player.level(), pos, state);
			DiffScreen.Target t = k < queue.size() ? forDecision(queue.get(k)) : defaultTarget();
			open(t);
		});
		registerDev();
	}

	// ------------------------------------------------------------------ targets

	public static void open(DiffScreen.Target target) {
		Minecraft.getInstance().gui.setScreen(new DiffScreen(target));
	}

	public static DiffScreen.Target forDecision(Decision d) {
		return new DiffScreen.Target(d.id(), d.repoId(), d.worktree());
	}

	/** A worktree, attached to its open merge decision (else its latest merge decision) when there is one. */
	public static DiffScreen.Target forWorktree(String repoId, String worktree) {
		ForemanState s = Foreman.state();
		if (s != null) {
			for (Decision d : s.openDecisions()) {
				if (d.kind() == Protocol.DecisionKind.MERGE && repoId.equals(d.repoId()) && worktree.equals(d.worktree())) {
					return forDecision(d);
				}
			}
			Decision latest = null;
			for (Decision d : s.decisions().values()) {
				if (d.kind() == Protocol.DecisionKind.MERGE && repoId.equals(d.repoId()) && worktree.equals(d.worktree()) && (latest == null || d
					.createdAt() > latest.createdAt())) {
					latest = d;
				}
			}
			if (latest != null) {
				return forDecision(latest);
			}
		}
		return new DiffScreen.Target(null, repoId, worktree);
	}

	/**
	 * The oldest open merge decision; else an active worktree with changes; else the latest merge
	 * decision of any status; else nothing.
	 */
	public static DiffScreen.Target defaultTarget() {
		ForemanState s = Foreman.state();
		if (s == null) {
			return new DiffScreen.Target(null, null, null);
		}
		Decision open = s.oldestOpen(Protocol.DecisionKind.MERGE);
		if (open != null && open.repoId() != null && open.worktree() != null) {
			return forDecision(open);
		}
		for (Repo r : s.repos().values()) {
			for (Worktree w : r.worktrees()) {
				if (w.status() == Protocol.WorktreeStatus.ACTIVE && w.files() > 0) {
					return new DiffScreen.Target(null, r.id(), w.id());
				}
			}
		}
		Decision latest = null;
		for (Decision d : s.decisions().values()) {
			if (d.kind() == Protocol.DecisionKind.MERGE && d.repoId() != null && d.worktree() != null && (latest == null || d.createdAt() > latest
				.createdAt())) {
				latest = d;
			}
		}
		return latest != null ? forDecision(latest) : new DiffScreen.Target(null, null, null);
	}

	private static DiffScreen.@Nullable Target targetFrom(Fields f) {
		ForemanState s = Foreman.state();
		String decisionId = f.has("decisionId") ? f.str("decisionId") : null;
		String repoId = f.has("repoId") ? f.str("repoId") : null;
		String worktree = f.has("worktree") ? f.str("worktree") : null;
		if (decisionId != null && !decisionId.isEmpty()) {
			Decision d = s == null ? null : s.decision(decisionId);
			if (d == null) {
				throw new DevBridge.DevException("no decision '" + decisionId + "' in the Foreman state");
			}
			return new DiffScreen.Target(d.id(), repoId != null ? repoId : d.repoId(), worktree != null ? worktree : d.worktree());
		}
		if (worktree != null) {
			if (repoId == null && s != null) {
				for (Repo r : s.repos().values()) {
					for (Worktree w : r.worktrees()) {
						if (worktree.equals(w.id())) {
							repoId = r.id();
						}
					}
				}
			}
			if (repoId == null) {
				throw new DevBridge.DevException("worktree '" + worktree + "' needs a repoId (no repo has it)");
			}
			return forWorktree(repoId, worktree);
		}
		return null;
	}

	// ------------------------------------------------------------------ DevBridge

	private static void registerDev() {
		DevBridge.register("dev.diff", 10_000,
			"{decisionId? | repoId?+worktree?, open?, fixture?, file?, scroll?, wrap?, syntax?, mode?: browse|feedback|confirm_merge|confirm_reject, feedback?} ->"
				+ " opens (or drives the open) diff review screen and returns its state; fixture:true shows a synthetic worst-case diff (QA)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				boolean forceOpen = f.optBool("open", false);
				Integer file = f.has("file") ? f.optInt("file", 0, 0, 10_000) : null;
				Double scroll = f.has("scroll") ? f.optNum("scroll", 0, 0, 10_000_000) : null;
				Boolean wrap = f.has("wrap") ? f.bool("wrap") : null;
				Boolean syntax = f.has("syntax") ? f.bool("syntax") : null;
				String mode = f.has("mode") ? f.nonBlank("mode") : null;
				String feedback = f.has("feedback") ? f.str("feedback") : null;
				boolean fixture = f.optBool("fixture", false);
				Boolean blur = f.has("blur") ? f.bool("blur") : null;
				return DevBridge.onClient(mc, () -> {
					if (blur != null) {
						ReviewKit.blurBehind = blur;
					}
					DiffScreen.Target t = targetFrom(f);
					Screen cur = mc.gui.screen();
					DiffScreen screen;
					if (fixture) {
						screen = DiffScreen.preview(DiffFixtures.stress());
						mc.gui.setScreen(screen);
					} else if (t != null || forceOpen || !(cur instanceof DiffScreen)) {
						screen = new DiffScreen(t != null ? t : defaultTarget());
						mc.gui.setScreen(screen);
					} else {
						screen = (DiffScreen) cur;
					}
					screen.applyDev(file, scroll == null ? null : scroll.floatValue(), wrap, syntax, mode, feedback);
					return screen.stateJson();
				});
			});
		DevBridge.register("dev.diff.state", 5_000, "{} -> state of the open diff review screen ({open:false} when none)",
			(req, mc) -> DevBridge.onClient(mc, () -> {
				if (mc.gui.screen() instanceof DiffScreen d) {
					JsonObject o = d.stateJson();
					o.addProperty("open", true);
					return o;
				}
				JsonObject o = new JsonObject();
				o.addProperty("open", false);
				JsonObject queue = new JsonObject();
				queue.addProperty("pendingMerges", MergeStationRenderer.queue().size());
				o.add("mergeStation", queue);
				return o;
			}));
		DevBridge.register("dev.review.guiScale", 10_000, "{scale: 0-6} -> sets the GUI scale (0 = auto) and resizes, for screen checks at 2/3/4",
			(req, mc) -> {
				int scale = Fields.of(req).optInt("scale", 3, 0, 6);
				return DevBridge.onClient(mc, () -> {
					mc.options.guiScale().set(scale);
					mc.resizeGui();
					JsonObject o = new JsonObject();
					o.addProperty("guiScale", mc.getWindow().getGuiScale());
					o.addProperty("guiWidth", mc.getWindow().getGuiScaledWidth());
					o.addProperty("guiHeight", mc.getWindow().getGuiScaledHeight());
					return o;
				});
			});
	}
}
