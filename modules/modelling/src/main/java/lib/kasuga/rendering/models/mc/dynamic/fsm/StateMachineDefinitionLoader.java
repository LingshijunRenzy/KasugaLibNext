package lib.kasuga.rendering.models.mc.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.sync.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.*;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lib.kasuga.core.resource.ResourceSystem;
import lib.kasuga.core.resource.ScopedResourceManager;
import lib.kasuga.core.resource.ScopedResourceManagerConsumer;
import lib.kasuga.core.resource.ScopedResourcePackListener;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads data-driven state machine definitions from {@code state_machines/*.json} in resource packs
 * and registers them with the injected {@link FsmDefinitions} (defaults to the shared bucket on
 * {@link FsmRegistries#GLOBAL}). Reloaded automatically when the scoped resource manager reloads.
 *
 * <p>File shape: every file is a wrapper object whose only top-level key is
 * {@value #FIELD_STATE_MACHINES}, mapping to an array of definitions —
 * {@code {"state_machines": [ <definition>, ... ]}}. Array elements are decoded with
 * {@link StateMachineDefinition#CODEC}, which is unchanged; the wrapper exists only at the file
 * layer, so inline/script registration, content hashing and programmatic registration are never
 * affected. One file may hold several definitions, and within a file a later entry with the same id
 * wins (last-wins), mirroring the cross-file contract.
 *
 * <p>Failure isolation: a shape violation (non-object body, missing/extra top-level key, non-array
 * value) rejects the whole file; a malformed array element only drops that element while its
 * siblings still load. Diagnostics are logged only — they are not recorded in the data-driven error
 * bucket yet (that wiring belongs to the reload-domain diagnostics phase).
 *
 * <p>Reload semantics: {@link #load(ResourceManager)} first clears the RESOURCE bucket
 * ({@link FsmDefinitions#clearResource()}) and re-populates it — already-built RESOURCE
 * machines keep running on the structure they were built from (definition bucket and instances are
 * decoupled by design); server-side machines only pick up new definitions when their block entity
 * is reloaded.
 */
@Context
public final class StateMachineDefinitionLoader implements ScopedResourceManagerConsumer, ScopedResourcePackListener {

    private static final Logger LOGGER = LogUtils.getLogger();

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

    private final FsmDefinitions definitions;

    @Inject
    ResourceSystem resourceSystem;

    public StateMachineDefinitionLoader() {
        this(FsmRegistries.GLOBAL.definitions());
    }

    /** Testable / host-injectable entry: pass a dedicated definition bucket. */
    public StateMachineDefinitionLoader(FsmDefinitions definitions) {
        this.definitions = definitions != null ? definitions : FsmRegistries.GLOBAL.definitions();
    }

    @PostConstruct
    public void init() {
        resourceSystem.registerConsumer(this);
    }

    @Override
    public void onResourceManagerAdded(@Nullable MinecraftServer server, ScopedResourceManager resourceManager) {
        resourceManager.addListener(this);
    }

    @Override
    public void onResourceManagerRemoved(@Nullable MinecraftServer server, ScopedResourceManager resourceManager) {
        // Machine instances are host-owned (never cleared here); the next reload re-populates definitions.
    }

    @Override
    public void onReloaded(ScopedResourceManager resourceManager) {
        load(resourceManager.getResourceManager());
    }

    public void load(ResourceManager resourceManager) {
        definitions.clearResource();
        Map<ResourceLocation, Resource> resources = resourceManager.listResources(PATH, loc -> loc.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            ResourceLocation loc = entry.getKey();
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                DecodedFile decoded = decodeFile(json);
                for (String error : decoded.errors()) {
                    LOGGER.error("Failed to decode state machine file '{}': {}", loc, error);
                }
                registerAll(loc, decoded.definitions());
            } catch (IOException | JsonParseException e) {
                LOGGER.error("Failed to read state machine definition {}", loc, e);
            }
        }
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
        if (!json.isJsonObject()) {
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

    /**
     * Registers the decoded definitions with in-file last-wins: when the same id appears more than once
     * in one file, only the last occurrence is registered and the superseded ones are reported (never
     * applied), mirroring the loader's cross-file last-wins contract.
     *
     * @param loc     the source file, used in diagnostics
     * @param decoded the definitions decoded from that file, in file order
     */
    private void registerAll(ResourceLocation loc, List<StateMachineDefinition> decoded) {
        Map<Id, StateMachineDefinition> winners = new LinkedHashMap<>();
        Set<Id> superseded = new LinkedHashSet<>();
        for (StateMachineDefinition definition : decoded) {
            if (winners.put(definition.id(), definition) != null) {
                superseded.add(definition.id());
            }
        }
        for (Id id : superseded) {
            LOGGER.warn("Duplicate state machine id '{}' in {}: an earlier entry was superseded by a later one "
                    + "(last-wins); the earlier entry was not registered", id, loc);
        }
        for (StateMachineDefinition definition : winners.values()) {
            Id id = definition.id();
            definitions.registerResource(id, definition);
            LOGGER.info("Loaded state machine definition '{}' from {}", id, loc);
        }
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
}
