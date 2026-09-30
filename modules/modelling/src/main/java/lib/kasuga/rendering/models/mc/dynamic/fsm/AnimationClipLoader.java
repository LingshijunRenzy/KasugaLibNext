package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;

import java.util.ArrayList;
import java.util.List;

/**
 * Decodes data-driven animation clip files of the reload domain. Like
 * {@link StateMachineDefinitionLoader} it is a pure file-layer decoder: no listener, no bucket and no
 * clear of its own — {@link ReloadIndexLoader} owns the reload cycle and turns the returned
 * diagnostics into paired log + bucket entries.
 *
 * <p>File shape: a wrapper object whose only top-level key is {@value #FIELD_ANIMATION_CLIPS},
 * mapping to an array of clips — {@code {"animation_clips": [ <clip>, ... ]}}. Each element is
 * decoded with {@link AnimationClip#CODEC}, which carries the clip's {@code id} itself (a required
 * {@code id} field, spelled with the same {@link lib.kasuga.rendering.models.uml.dynamic.fsm.Id} the
 * {@code states[].clip} reference uses), so no separate id key is extracted and an element is exactly
 * a clip. Keys the {@code AnimationClip} record does not name are ignored by the codec, so an author
 * may annotate an element without breaking the decode.
 *
 * <p>The file is only ever reached through an index manifest's {@code on_reload} array — there is no
 * directory glob for {@code animation_clips/}, unlike {@code state_machines/}.
 *
 * <p>Failure isolation mirrors the state machine decoder: a shape violation (non-object body, missing
 * or extra top-level key, non-array value) rejects the whole file; a malformed array element drops
 * only that element while its siblings still load.
 */
public final class AnimationClipLoader {

    /** The single top-level key every animation clip file is allowed to carry. */
    public static final String FIELD_ANIMATION_CLIPS = "animation_clips";

    /** The expected file shape, quoted verbatim in decode diagnostics. */
    private static final String EXPECTED_SHAPE = "{\"" + FIELD_ANIMATION_CLIPS + "\": [ <clip>, ... ]}";

    /**
     * Outcome of decoding one animation clip file.
     *
     * @param clips  the clips that decoded, in file order; empty when the whole file was rejected
     * @param errors human-readable diagnostics for a rejected file or a skipped array element; empty
     *               when the file decoded cleanly
     */
    public record DecodedFile(List<AnimationClip> clips, List<String> errors) {}

    /**
     * Decodes one animation clip file into its clips. The top-level shape is strict, mirroring the
     * state machine decoder and the content-file top-level field check of the registration domain: the
     * body must be an object whose only key is {@value #FIELD_ANIMATION_CLIPS}, holding an array. A
     * violation rejects the whole file. Elements are decoded one by one with {@link AnimationClip#CODEC}
     * so a single malformed element is skipped without dropping its siblings.
     *
     * @param json the parsed file body
     * @return the successfully decoded clips plus any diagnostics
     */
    public static DecodedFile decodeFile(JsonElement json) {
        List<String> errors = new ArrayList<>();
        if (json == null || !json.isJsonObject()) {
            errors.add("expected an object of shape " + EXPECTED_SHAPE + ", got " + describe(json));
            return new DecodedFile(List.of(), List.copyOf(errors));
        }
        JsonObject root = json.getAsJsonObject();
        if (!root.has(FIELD_ANIMATION_CLIPS)) {
            errors.add("missing top-level key '" + FIELD_ANIMATION_CLIPS + "' (expected shape "
                    + EXPECTED_SHAPE + ")");
            return new DecodedFile(List.of(), List.copyOf(errors));
        }
        if (root.size() != 1) {
            errors.add("only the top-level key '" + FIELD_ANIMATION_CLIPS + "' is allowed (expected shape "
                    + EXPECTED_SHAPE + "), found " + root.keySet());
            return new DecodedFile(List.of(), List.copyOf(errors));
        }
        JsonElement body = root.get(FIELD_ANIMATION_CLIPS);
        if (!body.isJsonArray()) {
            errors.add("top-level key '" + FIELD_ANIMATION_CLIPS + "' must be an array, got " + describe(body));
            return new DecodedFile(List.of(), List.copyOf(errors));
        }
        List<AnimationClip> decoded = new ArrayList<>();
        JsonArray array = body.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            final int index = i;
            if (!element.isJsonObject()) {
                errors.add(FIELD_ANIMATION_CLIPS + "[" + index + "] must be an object, got " + describe(element)
                        + "; element skipped");
                continue;
            }
            AnimationClip.CODEC.parse(JsonOps.INSTANCE, element)
                    .resultOrPartial(error -> errors.add(FIELD_ANIMATION_CLIPS + "[" + index
                            + "] failed to decode: " + error + "; element skipped"))
                    .ifPresent(decoded::add);
        }
        return new DecodedFile(List.copyOf(decoded), List.copyOf(errors));
    }

    /** Human-readable JSON kind for diagnostics. */
    private static String describe(JsonElement json) {
        if (json == null || json.isJsonNull()) {
            return "null";
        }
        if (json.isJsonArray()) {
            return "an array";
        }
        if (json.isJsonObject()) {
            return "an object";
        }
        if (json.isJsonPrimitive()) {
            return "a primitive (" + json + ")";
        }
        return json.toString();
    }

    private AnimationClipLoader() {}
}
