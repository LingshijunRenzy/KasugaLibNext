package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decodes data-driven state machine files and lists them on the pack stack. It is no longer a reload
 * participant: {@link ReloadIndexLoader} is the single reload orchestrator and owns the clear / read /
 * register cycle, so this class deliberately holds no listener, no clear and no bucket. It keeps the
 * two things the orchestrator needs per file — the canonical directory name ({@value #PATH}) with its
 * directory-glob discovery, and the strict file-level wrapper decode.
 *
 * <p>File shape: every file is a wrapper object whose only top-level key is
 * {@value #FIELD_STATE_MACHINES}, mapping to an array of definitions —
 * {@code {"state_machines": [ <definition>, ... ]}}. Array elements are decoded with
 * {@link StateMachineDefinition#CODEC}, which is unchanged; the wrapper exists only at the file
 * layer, so inline/script registration, content hashing and programmatic registration are never
 * affected. One file may hold several definitions, and within a file a later entry with the same id
 * wins (last-wins) — the last-wins resolution itself happens in the orchestrator, together with the
 * cross-entry order, so it is applied uniformly to both reload entries.
 *
 * <p>Failure isolation: a shape violation (non-object body, missing/extra top-level key, non-array
 * value) rejects the whole file; a malformed array element only drops that element while its
 * siblings still load. The orchestrator turns the returned diagnostics into paired log + bucket
 * entries.
 */
public final class StateMachineDefinitionLoader {

    /** Directory under {@code data/<ns>/} scanned for state machine files. */
    public static final String PATH = "state_machines";

    /** The single top-level key every state machine file is allowed to carry. */
    public static final String FIELD_STATE_MACHINES = "state_machines";

    /** The expected file shape, quoted verbatim in decode diagnostics. */
    private static final String EXPECTED_SHAPE = "{\"state_machines\": [ <definition>, ... ]}";

    /**
     * Outcome of decoding one state machine file.
     *
     * @param definitions the definitions that decoded, in file order; empty when the whole file was
     *                    rejected
     * @param errors      human-readable diagnostics for a rejected file or a skipped array element;
     *                    empty when the file decoded cleanly
     */
    public record DecodedFile(List<StateMachineDefinition> definitions, List<String> errors) {}

    /**
     * Discovers the {@code state_machines/*.json} files of one namespace through the pack stack's
     * directory glob, sorted by resource location. Kept next to {@link #PATH} so the canonical
     * directory and its discovery rule stay together; the reload orchestrator calls it and is
     * responsible for skipping files that the index already lists.
     *
     * @param resourceManager the pack stack to read from
     * @param namespace       the namespace to list, matched verbatim (no cross-namespace leakage)
     * @return the discovered files by resource location, sorted; empty when the namespace has none
     */
    public static Map<ResourceLocation, Resource> discoverFiles(ResourceManager resourceManager, String namespace) {
        return new TreeMap<>(resourceManager.listResources(PATH,
                loc -> loc.getNamespace().equals(namespace) && loc.getPath().endsWith(".json")));
    }

    /**
     * Decodes one state machine file into its definitions. The top-level shape is strict, mirroring the
     * content-file top-level field check in the data-driven dispatcher: the body must be an object whose
     * only key is {@value #FIELD_STATE_MACHINES}, holding an array. A violation rejects the whole file.
     * Elements are decoded one by one with {@link StateMachineDefinition#CODEC} so a single malformed
     * element is skipped without dropping its siblings.
     *
     * @param json the parsed file body
     * @return the successfully decoded definitions plus any diagnostics
     */
    public static DecodedFile decodeFile(JsonElement json) {
        List<String> errors = new ArrayList<>();
        if (json == null || !json.isJsonObject()) {
            errors.add("expected an object of shape " + EXPECTED_SHAPE + ", got " + describe(json));
            return new DecodedFile(List.of(), List.copyOf(errors));
        }
        JsonObject root = json.getAsJsonObject();
        if (!root.has(FIELD_STATE_MACHINES)) {
            errors.add("missing top-level key '" + FIELD_STATE_MACHINES + "' (expected shape "
                    + EXPECTED_SHAPE + ")");
            return new DecodedFile(List.of(), List.copyOf(errors));
        }
        if (root.size() != 1) {
            errors.add("only the top-level key '" + FIELD_STATE_MACHINES + "' is allowed (expected shape "
                    + EXPECTED_SHAPE + "), found " + root.keySet());
            return new DecodedFile(List.of(), List.copyOf(errors));
        }
        JsonElement body = root.get(FIELD_STATE_MACHINES);
        if (!body.isJsonArray()) {
            errors.add("top-level key '" + FIELD_STATE_MACHINES + "' must be an array, got " + describe(body));
            return new DecodedFile(List.of(), List.copyOf(errors));
        }
        List<StateMachineDefinition> decoded = new ArrayList<>();
        JsonArray array = body.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            final int index = i;
            if (!element.isJsonObject()) {
                errors.add(FIELD_STATE_MACHINES + "[" + index + "] must be an object, got " + describe(element)
                        + "; element skipped");
                continue;
            }
            StateMachineDefinition.CODEC.parse(JsonOps.INSTANCE, element)
                    .resultOrPartial(error -> errors.add(FIELD_STATE_MACHINES + "[" + index
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

    private StateMachineDefinitionLoader() {}
}
