package dev.orbit.cosmetic.common.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;

/**
 * Reads JSON from files Orbit did not write (models and settings shared by other players), the same way with every
 * Gson version Minecraft brings (1.20.1 ships Gson 2.10, 26.x ships 2.13): leniently, as Blockbench files need,
 * and refusing nesting deeper than a limit before parsing, so a hostile file cannot exhaust the stack. Newer Gson
 * versions have their own nesting limit; older ones do not, so the check is Orbit's own.
 */
public final class Json {
	private Json() {
	}

	/** Parses leniently; throws {@link JsonParseException} for bad JSON or nesting deeper than {@code maxDepth}. */
	public static JsonElement parseLenient(String text, int maxDepth) {
		checkDepth(text, maxDepth);
		JsonReader reader = new JsonReader(new StringReader(text));
		// setLenient is in every Gson version (setStrictness only since 2.11).
		reader.setLenient(true);
		return JsonParser.parseReader(reader);
	}

	/**
	 * Throws when arrays and objects nest deeper than {@code maxDepth}. Brackets in strings (double- or
	 * single-quoted, as lenient JSON allows) do not count.
	 */
	static void checkDepth(String text, int maxDepth) {
		int depth = 0;
		char quote = 0;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (quote != 0) {
				if (c == '\\') {
					i++;
				} else if (c == quote) {
					quote = 0;
				}
			} else if (c == '"' || c == '\'') {
				quote = c;
			} else if (c == '[' || c == '{') {
				if (++depth > maxDepth) {
					throw new JsonParseException("Nesting limit " + maxDepth + " reached at character " + i);
				}
			} else if (c == ']' || c == '}') {
				depth--;
			}
		}
	}
}
