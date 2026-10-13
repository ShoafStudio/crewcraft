package dev.agentcraft.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.agentcraft.client.dev.DevBridge.DevException;
import java.math.BigDecimal;

/**
 * Strict, typed access to DevBridge request fields. Every failure is a {@link DevException}
 * that names the field and what was received, e.g.
 * {@code field 'pitch' must be a finite number (got string "NaN")}.
 *
 * <ul>
 * <li>Numbers must be JSON numbers (not strings) and finite; NaN and Infinity are always refused.</li>
 * <li>Integers must be whole numbers within the given range (1.5 is refused, not truncated).</li>
 * <li>Booleans must be JSON true/false (the string "false" is refused, not read as false).</li>
 * <li>A field that is absent or JSON null counts as "not given" for the optional getters.</li>
 * </ul>
 *
 * <pre>
 * Fields f = Fields.of(req);
 * double x = f.num("x");
 * float pitch = (float) f.num("pitch", -90, 90);
 * int frames = f.optInt("frames", 3, 1, 600);
 * Fields look = f.optObj("lookAt");   // nested: errors say 'lookAt.x'
 * </pre>
 */
public final class Fields {
	private final JsonObject obj;
	private final String prefix;

	private Fields(JsonObject obj, String prefix) {
		this.obj = obj;
		this.prefix = prefix;
	}

	public static Fields of(JsonObject obj) {
		return new Fields(obj, "");
	}

	public JsonObject json() {
		return obj;
	}

	/** True when the field is present and not JSON null. */
	public boolean has(String field) {
		JsonElement e = obj.get(field);
		return e != null && !e.isJsonNull();
	}

	/** True when the field is present with an explicit JSON null. */
	public boolean isExplicitNull(String field) {
		JsonElement e = obj.get(field);
		return e != null && e.isJsonNull();
	}

	private String name(String field) {
		return "'" + prefix + field + "'";
	}

	private JsonElement required(String field) {
		JsonElement e = obj.get(field);
		if (e == null || e.isJsonNull()) {
			throw new DevException("missing field " + name(field));
		}
		return e;
	}

	/** Short description of a JSON value for error messages. */
	public static String describe(JsonElement e) {
		if (e == null || e instanceof JsonNull) {
			return "null";
		}
		if (e.isJsonObject()) {
			return "an object";
		}
		if (e.isJsonArray()) {
			return "an array";
		}
		JsonPrimitive p = e.getAsJsonPrimitive();
		String text = p.isString() ? DevBridge.GSON.toJson(p) : p.getAsString();
		if (text.length() > 60) {
			text = text.substring(0, 57) + "...";
		}
		return (p.isString() ? "string " : p.isBoolean() ? "boolean " : "number ") + text;
	}

	private DevException wrongType(String field, String expected, JsonElement got) {
		return new DevException("field " + name(field) + " must be " + expected + " (got " + describe(got) + ")");
	}

	// ------------------------------------------------------------------ numbers

	private double toDouble(String field, JsonElement e) {
		if (!(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber())) {
			throw wrongType(field, "a finite number", e);
		}
		double d;
		try {
			d = e.getAsDouble();
		} catch (NumberFormatException ex) {
			throw wrongType(field, "a finite number", e);
		}
		if (!Double.isFinite(d)) {
			throw wrongType(field, "a finite number", e);
		}
		return d;
	}

	private void checkRange(String field, double v, double min, double max) {
		if (v < min || v > max) {
			throw new DevException("field " + name(field) + " must be within [" + fmt(min) + ", " + fmt(max) + "] (got " + fmt(v) + ")");
		}
	}

	static String fmt(double d) {
		if (d == Math.rint(d) && Math.abs(d) < 1e15) {
			return Long.toString((long) d);
		}
		return Double.toString(d);
	}

	/** A required finite number. */
	public double num(String field) {
		return toDouble(field, required(field));
	}

	/** A required finite number within [min, max]. */
	public double num(String field, double min, double max) {
		double v = num(field);
		checkRange(field, v, min, max);
		return v;
	}

	/** An optional finite number; null when absent. */
	public Double optNum(String field) {
		return has(field) ? toDouble(field, obj.get(field)) : null;
	}

	/** An optional finite number within [min, max]; {@code def} when absent. */
	public double optNum(String field, double def, double min, double max) {
		if (!has(field)) {
			return def;
		}
		double v = toDouble(field, obj.get(field));
		checkRange(field, v, min, max);
		return v;
	}

	private long toLong(String field, JsonElement e, long min, long max) {
		if (!(e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber())) {
			throw wrongType(field, "an integer", e);
		}
		BigDecimal bd;
		try {
			bd = new BigDecimal(e.getAsString());
		} catch (NumberFormatException ex) {
			throw wrongType(field, "an integer", e);
		}
		long v;
		try {
			v = bd.stripTrailingZeros().longValueExact();
		} catch (ArithmeticException ex) {
			// Fractional, or outside the long range.
			if (bd.signum() != 0 && bd.stripTrailingZeros().scale() <= 0) {
				throw new DevException("field " + name(field) + " must be within [" + min + ", " + max + "] (got " + e.getAsString() + ")");
			}
			throw wrongType(field, "an integer", e);
		}
		if (v < min || v > max) {
			throw new DevException("field " + name(field) + " must be within [" + min + ", " + max + "] (got " + v + ")");
		}
		return v;
	}

	/** A required integer within [min, max]. */
	public long integer(String field, long min, long max) {
		return toLong(field, required(field), min, max);
	}

	/** An optional integer within [min, max]; {@code def} when absent. */
	public long optLong(String field, long def, long min, long max) {
		return has(field) ? toLong(field, obj.get(field), min, max) : def;
	}

	/** An optional int within [min, max]; {@code def} when absent. */
	public int optInt(String field, int def, int min, int max) {
		return (int) optLong(field, def, min, max);
	}

	// ------------------------------------------------------------------ booleans / strings / objects

	private boolean toBool(String field, JsonElement e) {
		if (!(e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean())) {
			throw wrongType(field, "true or false", e);
		}
		return e.getAsBoolean();
	}

	public boolean bool(String field) {
		return toBool(field, required(field));
	}

	public boolean optBool(String field, boolean def) {
		return has(field) ? toBool(field, obj.get(field)) : def;
	}

	/** An optional boolean; null when absent. */
	public Boolean optBool(String field) {
		return has(field) ? toBool(field, obj.get(field)) : null;
	}

	private String toStr(String field, JsonElement e) {
		if (!(e.isJsonPrimitive() && e.getAsJsonPrimitive().isString())) {
			throw wrongType(field, "a string", e);
		}
		return e.getAsString();
	}

	public String str(String field) {
		return toStr(field, required(field));
	}

	/** A required, non-blank string. */
	public String nonBlank(String field) {
		String s = str(field);
		if (s.isBlank()) {
			throw new DevException("field " + name(field) + " must not be empty");
		}
		return s;
	}

	public String optStr(String field, String def) {
		return has(field) ? toStr(field, obj.get(field)) : def;
	}

	/** A required nested object; its own errors are prefixed with this field's name. */
	public Fields obj(String field) {
		JsonElement e = required(field);
		if (!e.isJsonObject()) {
			throw wrongType(field, "an object", e);
		}
		return new Fields(e.getAsJsonObject(), prefix + field + ".");
	}

	/** An optional nested object; null when absent. */
	public Fields optObj(String field) {
		return has(field) ? obj(field) : null;
	}
}
