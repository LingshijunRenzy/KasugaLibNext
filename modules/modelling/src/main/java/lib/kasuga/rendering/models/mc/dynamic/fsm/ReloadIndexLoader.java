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
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.animation.ClipSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.DefinitionStateMachineFactory;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The single reload orchestrator of the RELOAD-DATA domain. On every resource reload it owns the
 * whole cycle: it clears the reload-sourced buckets <em>once</em>, discovers reload content through
 * both entries — the {@code state_machines/} directory glob and the {@code on_reload} arrays of the
 * {@code data/<ns>/kasuga_lib/data_driven/} index manifests — decodes each file, resolves duplicate
 * ids across both entries, registers the winners, and finally re-checks every definition it loaded
 * against the clip bucket.
 *
 * <p>Owning the cycle in one place is deliberate: {@link FsmDefinitions#clearResource()} (and with it
 * {@link FsmAnimationClips#clearResource()}) may be called exactly once per reload. A second listener
 * clearing independently would erase whatever the first one had just written, so
 * {@link StateMachineDefinitionLoader} no longer carries a listener or a clear of its own — it is only
 * the file decoder and the glob lister.
 *
 * <p><strong>Content types.</strong> A reload content file is routed by its top-level key, and the
 * resource type follows the shape — the domain is decided by the index array, never by the content
 * file. Two keys are known: {@code state_machines} (decoded by
 * {@link StateMachineDefinitionLoader#decodeFile}) and {@link AnimationClipLoader#FIELD_ANIMATION_CLIPS}
 * (decoded by {@link AnimationClipLoader#decodeFile}). Each decoder is strict about the file's key set,
 * so a file declaring both is rejected by both of them — reported, never silently dropped — and neither
 * type registers anything from it.
 *
 * <p><strong>Two entries</strong> are supported for state machines only: the directory glob and the
 * index. Animation clips are index-only — {@code animation_clips/} is not a glob directory, so a clip
 * file that no manifest lists is never read.
 *
 * <p><strong>Application order</strong> (glob first, index second; a later entry with the same id
 * wins, "last-wins"):
 * <ol>
 *   <li>every {@code data/<ns>/state_machines/*.json} file discovered by the directory glob, sorted
 *       by resource location;</li>
 *   <li>every file listed in an {@code on_reload} array — index manifests sorted by path, then the
 *       array order the manifest wrote, then the arrays inside a file in the order the decoders
 *       preserve (definitions / clips in file order).</li>
 * </ol>
 * A file that the index lists is skipped by the glob entry, so it is read (and its definitions
 * registered) exactly once instead of colliding with itself. Identity carries the namespace, so files
 * of different namespaces can never collide and the relative order of the two entries only matters
 * inside one namespace — where the glob still precedes the index.
 *
 * <p>A losing duplicate never reaches {@link FsmDefinitions#registerResource} /
 * {@link FsmAnimationClips#registerResource} — the registration domain's "the loser never has a side
 * effect" rule (its duplicate gate runs before {@code apply}) is reproduced here with the same pure
 * {@link DuplicateIdResolver}. Diagnostics are the pair "log + {@link Diagnostics} bucket" used by the
 * registration side; the bucket is addressed by the namespace the offending file lives in, so a
 * reload-triggered error never lands on another mod.
 *
 * <p><strong>Post-load clip check.</strong> After both buckets are populated, every definition this
 * cycle registered is checked with {@link DefinitionStateMachineFactory#unknownClipReferences} and a
 * dangling {@code states[].clip} is reported (WARN + bucket) naming the definition and the missing
 * clip. The check must run after the clips are loaded, or every reference would look dangling. It
 * never blocks a registration or changes the build-time degradation: an unresolved clip still falls
 * back to the state's static pose.
 */
@Context
public final class ReloadIndexLoader implements ScopedResourceManagerConsumer, ScopedResourcePackListener {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Top-level keys a reload-domain content file may declare, in a stable order for diagnostics.
     * Unknown keys are reported with the symmetric D9 hint pointing an author at {@code on_register}.
     */
    public static final Set<String> KNOWN_FIELDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(
                    StateMachineDefinitionLoader.FIELD_STATE_MACHINES, AnimationClipLoader.FIELD_ANIMATION_CLIPS)));

    /** Duplicate-resolution type name for the state machine definitions, mirroring the registration domain. */
    private static final String TYPE_STATE_MACHINES = StateMachineDefinitionLoader.FIELD_STATE_MACHINES;

    /** Duplicate-resolution type name for the animation clips — its own collision space. */
    private static final String TYPE_ANIMATION_CLIPS = AnimationClipLoader.FIELD_ANIMATION_CLIPS;

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
        // the same buckets, so neither can erase the other's writes. The clip clear drops only
        // reload-sourced clips -- clips registered by scripts / code keep their entry ("script wins").
        definitions.clearResource();
        clips.clearResource();

        List<String> namespaces = new ArrayList<>(resourceManager.getNamespaces());
        Collections.sort(namespaces);

        // Kept apart per entry so the unified application order below survives the per-namespace pass.
        List<DuplicateIdResolver.Candidate> globDefinitions = new ArrayList<>();
        List<DuplicateIdResolver.Candidate> indexDefinitions = new ArrayList<>();
        // Clips have one entry (the index) apart from a misplaced file under the glob's directory, so
        // their list is already in load order: glob-discovered ones first, then the on_reload ones.
        List<DuplicateIdResolver.Candidate> clipCandidates = new ArrayList<>();
        for (String namespace : namespaces) {
            // The index is read first: its on_reload arrays tell the glob entry which files it must
            // not read a second time.
            List<String> reloadPaths = readReloadPaths(resourceManager, namespace);
            collectGlob(resourceManager, namespace, new HashSet<>(reloadPaths), globDefinitions, clipCandidates);
            collectIndex(resourceManager, namespace, reloadPaths, indexDefinitions, clipCandidates);
        }

        // The unified order: every glob-discovered file first, then every on_reload-listed file.
        List<DuplicateIdResolver.Candidate> definitionCandidates = new ArrayList<>(globDefinitions);
        definitionCandidates.addAll(indexDefinitions);

        List<LoadedDefinition> registered = registerDefinitions(definitionCandidates);
        registerClips(clipCandidates);
        // After both buckets are populated: a clip reference can only be judged once the clip files of
        // this cycle have landed, otherwise every reference would look dangling.
        validateClipReferences(registered);
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
     * The glob's directory is the state machine one, so its files are the only ones it can add: a
     * {@code state_machines/} file that declares {@code animation_clips} still routes by its key (and
     * is reported by the clip decoder, which only knows that shape's own key rule).
     *
     * @param definitions sink for the definition candidates found
     * @param clips       sink for the clip candidates found
     */
    private void collectGlob(ResourceManager resourceManager, String namespace, Set<String> indexedPaths,
                             List<DuplicateIdResolver.Candidate> definitions,
                             List<DuplicateIdResolver.Candidate> clips) {
        for (Map.Entry<ResourceLocation, Resource> entry
                : StateMachineDefinitionLoader.discoverFiles(resourceManager, namespace).entrySet()) {
            ResourceLocation loc = entry.getKey();
            if (indexedPaths.contains(loc.getPath())) {
                continue;
            }
            readAndDispatch(namespace, "data/" + namespace + "/" + loc.getPath(), loc.getPath(),
                    entry.getValue(), definitions, clips);
        }
    }

    /**
     * Reads the content files one namespace's {@code on_reload} arrays point at. Paths are resolved
     * through the pack stack ({@link ResourceManager#getResource}), not the jar, so a data pack can
     * override reload-domain content.
     *
     * @param definitions sink for the definition candidates found
     * @param clips       sink for the clip candidates found
     */
    private void collectIndex(ResourceManager resourceManager, String namespace, List<String> reloadPaths,
                              List<DuplicateIdResolver.Candidate> definitions,
                              List<DuplicateIdResolver.Candidate> clips) {
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
            readAndDispatch(namespace, "data/" + namespace + "/" + path, path, resource.get(), definitions, clips);
        }
    }

    /** Reads and dispatches one content file; read failures become a paired diagnostic, never an exception. */
    private void readAndDispatch(String namespace, String label, String sourcePath, Resource resource,
                                 List<DuplicateIdResolver.Candidate> definitions,
                                 List<DuplicateIdResolver.Candidate> clips) {
        try (BufferedReader reader = resource.openAsReader()) {
            dispatch(namespace, label, sourcePath, JsonParser.parseReader(reader), definitions, clips);
        } catch (IOException | JsonParseException e) {
            reportError(namespace, "Failed to read content file '" + label + "'", e);
        }
    }

    /**
     * Routes one content document by its top-level keys — {@code state_machines} decodes through
     * {@link StateMachineDefinitionLoader#decodeFile}, {@code animation_clips} through
     * {@link AnimationClipLoader#decodeFile}. An unknown top-level key is reported with the hint that
     * points an author whose file is actually registration content back at {@code on_register},
     * mirroring the registration side's D9 hint toward {@code on_reload}.
     *
     * <p>Each present key is decoded independently, and each decoder rejects a file that carries any
     * other key — so a file mixing both types is reported twice and contributes nothing, instead of
     * having one of its arrays silently ignored.
     */
    private void dispatch(String namespace, String label, String sourcePath, @Nullable JsonElement json,
                          List<DuplicateIdResolver.Candidate> definitions,
                          List<DuplicateIdResolver.Candidate> clips) {
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
                definitions.add(new DuplicateIdResolver.Candidate(TYPE_STATE_MACHINES,
                        definition.id().toString(), label, new LoadedDefinition(namespace, definition)));
            }
        }
        if (root.has(AnimationClipLoader.FIELD_ANIMATION_CLIPS)) {
            AnimationClipLoader.DecodedFile decoded = AnimationClipLoader.decodeFile(json);
            for (String error : decoded.errors()) {
                reportError(namespace, "Failed to decode animation clip file '" + label + "': " + error);
            }
            for (AnimationClip clip : decoded.clips()) {
                clips.add(new DuplicateIdResolver.Candidate(TYPE_ANIMATION_CLIPS,
                        clip.id().toString(), label, new LoadedClip(namespace, clip)));
            }
        }
    }

    /**
     * Resolves duplicate ids among the definition candidates and registers the winners, returning the
     * definitions that actually landed in the bucket so the clip check can run over exactly them.
     */
    private List<LoadedDefinition> registerDefinitions(List<DuplicateIdResolver.Candidate> candidates) {
        List<LoadedDefinition> registered = new ArrayList<>();
        for (DuplicateIdResolver.Candidate winner : resolveAndReport(candidates)) {
            LoadedDefinition loaded = (LoadedDefinition) winner.payload();
            StateMachineDefinition definition = loaded.definition();
            definitions.registerResource(definition.id(), definition);
            registered.add(loaded);
            LOGGER.info("Loaded state machine definition '{}' from '{}' (reload domain, mod '{}')",
                    definition.id(), winner.sourcePath(), loaded.modId());
        }
        return registered;
    }

    /**
     * Registers the winning clip candidates into the reload bucket. {@link ClipSampler} is the sampler
     * for the {@link AnimationClip} data these files carry; a SCRIPT clip of the same id is left
     * untouched by {@link FsmAnimationClips#registerResource} ("script wins"), so a file can never
     * shadow a clip a script registered.
     */
    private void registerClips(List<DuplicateIdResolver.Candidate> candidates) {
        for (DuplicateIdResolver.Candidate winner : resolveAndReport(candidates)) {
            LoadedClip loaded = (LoadedClip) winner.payload();
            AnimationClip clip = loaded.clip();
            clips.registerResource(clip.id(), ClipSampler.INSTANCE, clip);
            LOGGER.info("Loaded animation clip '{}' from '{}' (reload domain, mod '{}')",
                    clip.id(), winner.sourcePath(), loaded.modId());
        }
    }

    /**
     * Last-wins resolution of one type's candidates plus the paired diagnostic for every loser. A
     * losing duplicate is reported (WARN + bucket) but never registered, so the id keeps exactly one
     * reload-sourced entry.
     *
     * @return the surviving candidates, in their original relative order
     */
    private List<DuplicateIdResolver.Candidate> resolveAndReport(List<DuplicateIdResolver.Candidate> candidates) {
        DuplicateIdResolver.Result result = DuplicateIdResolver.resolve(candidates);
        for (DuplicateIdResolver.Conflict conflict : result.conflicts()) {
            LOGGER.warn(DuplicateIdResolver.describe(conflict));
            Diagnostics.report(modIdOf(conflict.loser()), DuplicateIdResolver.toLoadingError(conflict));
        }
        return result.winners();
    }

    /**
     * Post-load reference check: every definition this cycle registered is inspected for states whose
     * {@code clip} points at an id that no clip was registered under, and each such definition is
     * reported once (WARN + bucket) with the definition id and the dangling references. Without this
     * pass a dangling clip would only surface when something actually builds a machine from the
     * definition — that is, after a block is placed in the world.
     *
     * <p>Nothing is blocked, re-registered or degraded differently from the build-time behaviour: an
     * unresolved clip still falls back to the state's static pose. Only resource-loaded definitions are
     * checked; a script definition shadowing a same-id resource entry is not ours to judge, so the
     * candidate is skipped when it is no longer the live entry in the bucket.
     */
    private void validateClipReferences(List<LoadedDefinition> registered) {
        for (LoadedDefinition loaded : registered) {
            StateMachineDefinition definition = loaded.definition();
            if (definitions.get(definition.id()) != definition) {
                continue;
            }
            List<String> missing = DefinitionStateMachineFactory.unknownClipReferences(definition, clips);
            if (!missing.isEmpty()) {
                reportWarning(loaded.modId(), "State machine definition '" + definition.id()
                        + "' has unresolved clip references after reload: " + missing
                        + "; the affected states degrade to their static pose");
            }
        }
    }

    /** A decoded definition together with the namespace it was found in, so diagnostics attribute to it. */
    private record LoadedDefinition(String modId, StateMachineDefinition definition) implements Loaded {}

    /** A decoded clip together with the namespace it was found in, so diagnostics attribute to it. */
    private record LoadedClip(String modId, AnimationClip clip) implements Loaded {}

    /** A decoded reload-domain entry plus the namespace it was found in, so diagnostics attribute to it. */
    private interface Loaded {
        String modId();
    }

    private static String modIdOf(DuplicateIdResolver.Candidate candidate) {
        return ((Loaded) candidate.payload()).modId();
    }

    /** Logs and records one reload-domain diagnostic under the namespace that owns the file. */
    private static void reportError(String modId, String message) {
        reportError(modId, message, null);
    }

    private static void reportError(String modId, String message, @Nullable Throwable cause) {
        LOGGER.error(message, cause);
        Diagnostics.report(modId, cause == null ? new IllegalStateException(message) : new IOException(message, cause));
    }

    /**
     * Logs (WARN, matching the build-time aggregate warning of the same check) and records one
     * reload-domain diagnostic under the namespace that owns the file.
     */
    private static void reportWarning(String modId, String message) {
        LOGGER.warn(message);
        Diagnostics.report(modId, new IllegalStateException(message));
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
