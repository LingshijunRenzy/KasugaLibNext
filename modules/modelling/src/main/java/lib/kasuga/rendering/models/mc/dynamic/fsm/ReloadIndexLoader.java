package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lib.kasuga.core.resource.ResourceSystem;
import lib.kasuga.core.resource.ScopedResourceManager;
import lib.kasuga.core.resource.ScopedResourceManagerConsumer;
import lib.kasuga.core.resource.ScopedResourcePackListener;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.dedup.DuplicateIdResolver;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The single reload orchestrator of the RELOAD-DATA domain. On every resource reload it owns the
 * whole cycle: it clears the reload-sourced buckets <em>once</em>, discovers state machine content
 * through both entries — the {@code state_machines/} directory glob and the {@code on_reload} arrays
 * of the {@code data/<ns>/kasuga_lib/data_driven/} index manifests — decodes each file, resolves
 * duplicate ids across both entries, and only then registers the winners.
 *
 * <p>Owning the cycle in one place is deliberate: {@link FsmDefinitions#clearResource()} may be
 * called exactly once per reload. A second listener clearing independently would erase whatever the
 * first one had just written, so {@link StateMachineDefinitionLoader} no longer carries a listener or
 * a clear of its own — it is only the file decoder and the glob lister.
 *
 * <p><strong>Application order</strong> (glob first, index second; a later entry with the same id
 * wins, "last-wins"):
 * <ol>
 *   <li>every {@code data/<ns>/state_machines/*.json} file discovered by the directory glob, sorted
 *       by resource location;</li>
 *   <li>every file listed in an {@code on_reload} array — index manifests sorted by path, then the
 *       array order the manifest wrote, then the {@code state_machines} array order inside a file.</li>
 * </ol>
 * A file that the index lists is skipped by the glob entry, so it is read (and its definitions
 * registered) exactly once instead of colliding with itself.
 *
 * <p>A losing duplicate never reaches {@link FsmDefinitions#registerResource} — the registration
 * domain's "the loser never has a side effect" rule (its duplicate gate runs before {@code apply})
 * is reproduced here with the same pure {@link DuplicateIdResolver}. Diagnostics are the pair
 * "log + {@link Diagnostics} bucket" used by the registration side; the bucket is addressed by the
 * namespace the offending file lives in, so a reload-triggered error never lands on another mod.
 */
@Context
public final class ReloadIndexLoader implements ScopedResourceManagerConsumer, ScopedResourcePackListener {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The reload domain's only content type so far that the routing dispatch does not consume yet. */
    private static final String FIELD_ANIMATION_CLIPS = "animation_clips";

    /**
     * Top-level keys a reload-domain content file may declare, in a stable order for diagnostics.
     * Unknown keys are reported with the symmetric D9 hint pointing an author at {@code on_register};
     * {@code animation_clips} is a recognised reload-domain key whose loading lands in a later stage.
     */
    public static final Set<String> KNOWN_FIELDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(
                    StateMachineDefinitionLoader.FIELD_STATE_MACHINES, FIELD_ANIMATION_CLIPS)));

    /** Duplicate-resolution type name for the state machine definitions, mirroring the registration domain. */
    private static final String TYPE_STATE_MACHINES = StateMachineDefinitionLoader.FIELD_STATE_MACHINES;

    private final FsmDefinitions definitions;
    private final FsmAnimationClips clips;

    @Inject
    ResourceSystem resourceSystem;

    public ReloadIndexLoader() {
        this(FsmRegistries.GLOBAL.definitions(), FsmRegistries.GLOBAL.clips());
    }

    /** Testable / host-injectable entry: pass dedicated buckets. */
    public ReloadIndexLoader(FsmDefinitions definitions, FsmAnimationClips clips) {
        this.definitions = definitions != null ? definitions : FsmRegistries.GLOBAL.definitions();
        this.clips = clips != null ? clips : FsmRegistries.GLOBAL.clips();
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
        reload(resourceManager.getResourceManager());
    }

    /**
     * Runs one complete reload cycle against a resource manager.
     *
     * @param resourceManager the pack stack to read from; must not be {@code null}
     */
    public void reload(ResourceManager resourceManager) {
        // Exactly one clear per cycle (the orchestrator owns it): both entries below then populate
        // the same bucket, so neither can erase the other's writes.
        definitions.clearResource();
        // The clip bucket's reload half is cleared here as well. Nothing registers reload-sourced
        // clips yet (clip loading is a later stage), so this only pre-wires the cycle: when that
        // consumer lands it must not own a second clear. Code/scripting clips survive either way.
        clips.clearResource();

        List<String> namespaces = new ArrayList<>(resourceManager.getNamespaces());
        Collections.sort(namespaces);

        Map<String, List<DuplicateIdResolver.Candidate>> globByNamespace = new LinkedHashMap<>();
        Map<String, List<DuplicateIdResolver.Candidate>> indexByNamespace = new LinkedHashMap<>();
        for (String namespace : namespaces) {
            // The index is read first: its on_reload arrays tell the glob entry which files it must
            // not read a second time.
            List<String> reloadPaths = readReloadPaths(resourceManager, namespace);
            globByNamespace.put(namespace, collectGlob(resourceManager, namespace, new HashSet<>(reloadPaths)));
            indexByNamespace.put(namespace, collectIndex(resourceManager, namespace, reloadPaths));
        }

        // The unified order: every glob-discovered file first, then every on_reload-listed file.
        List<DuplicateIdResolver.Candidate> candidates = new ArrayList<>();
        globByNamespace.values().forEach(candidates::addAll);
        indexByNamespace.values().forEach(candidates::addAll);

        apply(candidates);
    }

    /**
     * The index directory as a {@link ResourceManager} path, derived from
     * {@link JsonTreeBuilder#indexDirectorySegments(String)} so the jar reader (registration) and the
     * pack-stack reader (reload) share one spelling. A pack-stack path is relative to
     * {@code data/<ns>/} — the pack already provides that prefix — while the canonical segments start
     * with {@code {"data", <mod>, ...}}, so the leading pair is dropped:
     * {@code {"data", "kasuga_lib", "kasuga_lib", "data_driven"}} → {@code "kasuga_lib/data_driven"}.
     *
     * @param modId the owning mod's id, passed through to {@link JsonTreeBuilder#indexDirectorySegments}
     * @return the pack-stack path of the index directory
     */
    public static String indexResourcePath(String modId) {
        String[] segments = JsonTreeBuilder.indexDirectorySegments(modId);
        if (segments.length < 3) {
            throw new IllegalStateException(
                    "Unexpected data-driven index layout: " + Arrays.toString(segments));
        }
        return String.join("/", Arrays.copyOfRange(segments, 2, segments.length));
    }

    /**
     * Reads the {@code on_reload} arrays of one namespace's index manifests, reusing the registration
     * domain's reader: the manifests are parsed with {@link JsonTreeBuilder#parseIndexManifest} and
     * aggregated with {@link JsonTreeBuilder#resolveIndexManifests}, so D6's fail-closed cross-listing
     * (a path in both arrays is consumed by neither domain) applies identically on this side.
     *
     * @return the {@code on_reload} content paths, or an empty list when the namespace ships no index
     */
    private List<String> readReloadPaths(ResourceManager resourceManager, String namespace) {
        Map<ResourceLocation, Resource> found = resourceManager.listResources(indexResourcePath(namespace),
                loc -> loc.getNamespace().equals(namespace) && loc.getPath().endsWith(".json"));
        if (found.isEmpty()) {
            return List.of();
        }

        List<JsonTreeBuilder.IndexManifest> manifests = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry : new TreeMap<>(found).entrySet()) {
            ResourceLocation loc = entry.getKey();
            JsonObject root;
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                if (json == null || json.isJsonNull()) {
                    root = null; // empty file: reported as "declares neither" by the manifest parser
                } else if (json.isJsonObject()) {
                    root = json.getAsJsonObject();
                } else {
                    reportError(namespace, "Index file '" + loc + "' must be a JSON object, got " + describe(json));
                    continue;
                }
            } catch (IOException | JsonParseException e) {
                reportError(namespace, "Failed to read data-driven index " + loc, e);
                continue;
            }
            manifests.add(JsonTreeBuilder.parseIndexManifest(namespace, loc.getPath(), root));
        }
        if (manifests.isEmpty()) {
            return List.of();
        }
        return JsonTreeBuilder.resolveIndexManifests(namespace, manifests).onReloadPaths();
    }

    /**
     * Discovers one namespace's {@code state_machines/*.json} files by directory glob, skipping any
     * file the namespace's {@code on_reload} arrays already list (it is read once, through the index).
     */
    private List<DuplicateIdResolver.Candidate> collectGlob(ResourceManager resourceManager, String namespace,
                                                            Set<String> indexedPaths) {
        List<DuplicateIdResolver.Candidate> candidates = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry
                : StateMachineDefinitionLoader.discoverFiles(resourceManager, namespace).entrySet()) {
            ResourceLocation loc = entry.getKey();
            if (indexedPaths.contains(loc.getPath())) {
                continue;
            }
            readAndDispatch(namespace, "data/" + namespace + "/" + loc.getPath(), loc.getPath(),
                    entry.getValue(), candidates);
        }
        return candidates;
    }

    /**
     * Reads the content files one namespace's {@code on_reload} arrays point at. Paths are resolved
     * through the pack stack ({@link ResourceManager#getResource}), not the jar, so a data pack can
     * override reload-domain content.
     */
    private List<DuplicateIdResolver.Candidate> collectIndex(ResourceManager resourceManager, String namespace,
                                                             List<String> reloadPaths) {
        List<DuplicateIdResolver.Candidate> candidates = new ArrayList<>();
        for (String path : reloadPaths) {
            String invalid = JsonTreeBuilder.validateSourcePath(path);
            if (invalid != null) {
                reportError(namespace, "Invalid 'on_reload' path '" + path + "' for mod '" + namespace + "': "
                        + invalid);
                continue;
            }
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(namespace, path);
            Optional<Resource> resource = resourceManager.getResource(loc);
            if (resource.isEmpty()) {
                reportError(namespace, "Source file not found for mod '" + namespace + "': data/"
                        + namespace + "/" + path);
                continue;
            }
            readAndDispatch(namespace, "data/" + namespace + "/" + path, path, resource.get(), candidates);
        }
        return candidates;
    }

    /** Reads and dispatches one content file; read failures become a paired diagnostic, never an exception. */
    private void readAndDispatch(String namespace, String label, String sourcePath, Resource resource,
                                 List<DuplicateIdResolver.Candidate> candidates) {
        try (BufferedReader reader = resource.openAsReader()) {
            dispatch(namespace, label, sourcePath, JsonParser.parseReader(reader), candidates);
        } catch (IOException | JsonParseException e) {
            reportError(namespace, "Failed to read state machine file '" + label + "'", e);
        }
    }

    /**
     * Routes one content document by its top-level keys — {@code state_machines} decodes through
     * {@link StateMachineDefinitionLoader#decodeFile}; {@code animation_clips} is recognised but has
     * no consumer yet. An unknown top-level key is reported with the hint that points an author whose
     * file is actually registration content back at {@code on_register}, mirroring the registration
     * side's D9 hint toward {@code on_reload}.
     */
    private void dispatch(String namespace, String label, String sourcePath, @Nullable JsonElement json,
                          List<DuplicateIdResolver.Candidate> candidates) {
        if (json == null || !json.isJsonObject()) {
            reportError(namespace, "Content file '" + label + "' must be a JSON object, got " + describe(json));
            return;
        }
        JsonObject root = json.getAsJsonObject();
        for (String field : root.keySet()) {
            if (KNOWN_FIELDS.contains(field)) {
                continue;
            }
            reportError(namespace, "Content file '" + label + "' contains unsupported top-level field '"
                    + field + "'; reload-domain files may only contain " + KNOWN_FIELDS
                    + ". If these fields are registration content (e.g. a 'blocks', 'items' or "
                    + "'registry_groups' array), list the file under 'on_register' instead");
        }

        if (root.has(StateMachineDefinitionLoader.FIELD_STATE_MACHINES)) {
            StateMachineDefinitionLoader.DecodedFile decoded = StateMachineDefinitionLoader.decodeFile(json);
            for (String error : decoded.errors()) {
                reportError(namespace, "Failed to decode state machine file '" + label + "': " + error);
            }
            for (StateMachineDefinition definition : decoded.definitions()) {
                candidates.add(new DuplicateIdResolver.Candidate(TYPE_STATE_MACHINES,
                        definition.id().toString(), label, new LoadedDefinition(namespace, definition)));
            }
        } else if (root.has(FIELD_ANIMATION_CLIPS)) {
            LOGGER.debug("Content file '{}' for mod '{}' declares '{}'; reload-sourced clips are not consumed yet",
                    label, namespace, FIELD_ANIMATION_CLIPS);
        }
    }

    /**
     * Resolves duplicate ids across both entries and registers the winners. A losing duplicate is
     * reported (WARN + bucket) but never registered, so the id keeps exactly one reload-sourced entry.
     */
    private void apply(List<DuplicateIdResolver.Candidate> candidates) {
        DuplicateIdResolver.Result result = DuplicateIdResolver.resolve(candidates);
        for (DuplicateIdResolver.Conflict conflict : result.conflicts()) {
            LOGGER.warn(DuplicateIdResolver.describe(conflict));
            Diagnostics.report(modIdOf(conflict.loser()), DuplicateIdResolver.toLoadingError(conflict));
        }
        for (DuplicateIdResolver.Candidate candidate : result.winners()) {
            LoadedDefinition loaded = (LoadedDefinition) candidate.payload();
            StateMachineDefinition definition = loaded.definition();
            definitions.registerResource(definition.id(), definition);
            LOGGER.info("Loaded state machine definition '{}' from '{}' (reload domain, mod '{}')",
                    definition.id(), candidate.sourcePath(), loaded.modId());
        }
    }

    /** A decoded definition together with the namespace it was found in, so diagnostics attribute to it. */
    private record LoadedDefinition(String modId, StateMachineDefinition definition) {}

    private static String modIdOf(DuplicateIdResolver.Candidate candidate) {
        return ((LoadedDefinition) candidate.payload()).modId();
    }

    /** Logs and records one reload-domain diagnostic under the namespace that owns the file. */
    private static void reportError(String modId, String message) {
        reportError(modId, message, null);
    }

    private static void reportError(String modId, String message, @Nullable Throwable cause) {
        LOGGER.error(message, cause);
        Diagnostics.report(modId, cause == null ? new IllegalStateException(message) : new IOException(message, cause));
    }

    /** Human-readable JSON kind for diagnostics. */
    private static String describe(@Nullable JsonElement json) {
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
