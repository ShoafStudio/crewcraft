package dev.agentcraft.hq;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/** Registry of HQ builders. Phase 3's real HQ registers itself here and calls {@link #setDefault}. */
public final class HqBuilders {
	private static final Map<String, HqBuilder> BUILDERS = new ConcurrentHashMap<>();
	private static volatile String defaultId = TestRoomBuilder.ID;

	private HqBuilders() {
	}

	public static void register(HqBuilder builder) {
		BUILDERS.put(builder.id(), builder);
	}

	public static void setDefault(String id) {
		defaultId = id;
	}

	public static String defaultId() {
		return defaultId;
	}

	public static @Nullable HqBuilder get(String id) {
		return BUILDERS.get(id);
	}

	public static Set<String> ids() {
		return Set.copyOf(BUILDERS.keySet());
	}
}
