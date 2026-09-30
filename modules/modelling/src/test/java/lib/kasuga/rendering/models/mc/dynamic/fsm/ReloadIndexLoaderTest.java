package lib.kasuga.rendering.models.mc.dynamic.fsm;

import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the reload orchestrator against a stub {@link ResourceManager}: one clear per cycle, the two
 * discovery entries (directory glob and the index's {@code on_reload} array), the interaction between
 * them, cross-entry last-wins, and the paired diagnostics.
 *
 * <p>This is a plain JVM test — no mod jar and no running game; the pack stack is a plain map of
 * virtual files. The wrapper/decode contract itself is locked down in
 * {@code StateMachineDefinitionLoaderTest}.
 */
class ReloadIndexLoaderTest {

    private static final String NS = "reload_test";

    private static final ResourceLocation GOOD_ID = ResourceLocation.fromNamespaceAndPath(NS, "good");

    private static final String GOOD_JSON = """
            {
              "state_machines": [
                { "id": "reload_test:good",
                  "layers": [ { "id": "l", "initial_state": "idle",
                    "states": [ { "id": "idle", "duration_ticks": 10 } ] } ] }
              ]
            }
            """;

    private static final String BROKEN_JSON = "{ this is not json }";

    /** The same id twice in one file; the second entry declares a state var, so the winner is identifiable. */
    private static final String DUPLICATE_JSON = """
            {
              "state_machines": [
                { "id": "reload_test:good", "layers": [ { "id": "l", "initial_state": "idle" } ] },
                { "id": "reload_test:good", "state_vars": [ { "name": "x" } ],
                  "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ]
            }
            """;

    /** Glob copy of {@code reload_test:dup}: declares one state var, so a glob win is distinguishable. */
    private static final String GLOB_DUPLICATE_JSON = """
            {
              "state_machines": [
                { "id": "reload_test:dup", "state_vars": [ { "name": "from_glob" } ],
                  "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ]
            }
            """;

    /** Index copy of {@code reload_test:dup}: two state vars, so an index win is distinguishable. */
    private static final String INDEX_DUPLICATE_JSON = """
            {
              "state_machines": [
                { "id": "reload_test:dup",
                  "state_vars": [ { "name": "from_index_a" }, { "name": "from_index_b" } ],
                  "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ]
            }
            """;

    private FsmDefinitions definitions;

    @BeforeEach
    void freshBucket() {
        definitions = new FsmDefinitions();
        JsonTreeBuilder.clearLoadingErrors(NS);
    }

    @AfterEach
    void clearBucket() {
        JsonTreeBuilder.clearLoadingErrors(NS);
    }

    private ReloadIndexLoader orchestrator() {
        return new ReloadIndexLoader(definitions, new FsmAnimationClips());
    }

    private static TestResourceManager manager(String path, String content) {
        return new TestResourceManager().add(NS, path, content);
    }

    private static String indexManifest(String onReloadEntry) {
        return "{ \"on_reload\": [ \"" + onReloadEntry + "\" ] }";
    }

    // --- the reload cycle ---

    @Test
    void loadsValidDefinitionsIntoInjectedBucket() {
        orchestrator().reload(manager("state_machines/good.json", GOOD_JSON));

        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")));
        assertEquals(List.of(), Diagnostics.errors(NS), "a clean load must not report anything");
    }

    @Test
    void brokenJsonDoesNotAbortTheBatch() {
        orchestrator().reload(new TestResourceManager()
                .add(NS, "state_machines/broken.json", BROKEN_JSON)
                .add(NS, "state_machines/good.json", GOOD_JSON));

        assertNull(definitions.get(Id.fromNamespaceAndPath(NS, "broken")));
        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")), "a broken file must not abort the batch");
    }

    @Test
    void reloadReplacesResourceDefinitionsAndKeepsScriptDefinitions() {
        ReloadIndexLoader orchestrator = orchestrator();
        orchestrator.reload(manager("state_machines/good.json", GOOD_JSON));
        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")));

        // a script definition on the same registry must survive reloads
        Id scriptId = Id.fromNamespaceAndPath(NS, "script_def");
        definitions.register(scriptId, definitions.get(Id.fromNamespaceAndPath(NS, "good")));

        // second cycle with an empty pack: RESOURCE definitions go away, SCRIPT stays
        orchestrator.reload(new TestResourceManager());
        assertNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")));
        assertNotNull(definitions.get(scriptId));
    }

    @Test
    void hashTracksDefinitionIdentityAcrossReloadAndOverwrite() {
        ReloadIndexLoader orchestrator = orchestrator();
        Id good = Id.fromNamespaceAndPath(NS, "good");
        assertEquals(0, definitions.hash(good), "absent id hashes to 0");

        orchestrator.reload(manager("state_machines/good.json", GOOD_JSON));
        int loaded = definitions.hash(good);
        assertNotEquals(0, loaded, "a loaded definition has a non-zero content hash");

        orchestrator.reload(manager("state_machines/good.json", GOOD_JSON));
        assertEquals(loaded, definitions.hash(good), "same content -> same hash");

        definitions.register(good, new StateMachineDefinition(good, List.of(), List.of()));
        assertNotEquals(loaded, definitions.hash(good), "different content -> different hash");
    }

    @Test
    void sameJsonCanBeLoadedTwiceWithoutError() {
        ReloadIndexLoader orchestrator = orchestrator();
        TestResourceManager pack = manager("state_machines/good.json", GOOD_JSON);
        orchestrator.reload(pack);
        orchestrator.reload(pack);
        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")));
        assertEquals(List.of(), Diagnostics.errors(NS));
    }

    @Test
    void duplicateIdWithinFileLastWins() {
        orchestrator().reload(manager("state_machines/dup.json", DUPLICATE_JSON));

        StateMachineDefinition loaded = definitions.get(Id.fromNamespaceAndPath(NS, "good"));
        assertNotNull(loaded, "an id present in the file must be registered");
        assertEquals(1, loaded.stateVars().size(),
                "the later in-file entry wins (the loser declares no state var)");
        assertTrue(errorsContain("Duplicate id 'reload_test:good'"),
                "the superseded in-file entry must be reported: " + Diagnostics.errors(NS));
    }

    // --- the two entries and how they interact ---

    /**
     * A file listed in {@code on_reload} is read once: the glob entry skips it. Reading it twice would
     * collide with itself and re-register the same id.
     */
    @Test
    void globAndIndexListingTheSameFileReadItOnce() {
        AtomicInteger invalidations = new AtomicInteger();
        definitions.addListener(id -> invalidations.incrementAndGet());

        orchestrator().reload(new TestResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", indexManifest("state_machines/good.json"))
                .add(NS, "state_machines/good.json", GOOD_JSON));

        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")));
        assertEquals(0, invalidations.get(),
                "re-registering an id would notify the invalidation listener; the file must be read once");
        assertEquals(List.of(), Diagnostics.errors(NS), "a single read has no conflict to report");
    }

    /**
     * Two different files declare the same id: the index entry is applied after the glob entry, so the
     * index definition wins and the glob one is never registered (last-wins with no side effect for the
     * loser).
     */
    @Test
    void indexEntryWinsOverTheGlobEntryAndTheLoserIsNotRegistered() {
        AtomicInteger invalidations = new AtomicInteger();
        definitions.addListener(id -> invalidations.incrementAndGet());

        orchestrator().reload(new TestResourceManager()
                .add(NS, "state_machines/a.json", GLOB_DUPLICATE_JSON)
                .add(NS, "kasuga_lib/data_driven/index.json", indexManifest("content/b.json"))
                .add(NS, "content/b.json", INDEX_DUPLICATE_JSON));

        StateMachineDefinition loaded = definitions.get(Id.fromNamespaceAndPath(NS, "dup"));
        assertNotNull(loaded, "the id must be registered");
        assertEquals(2, loaded.stateVars().size(), "the later (index-listed) definition must win");
        assertEquals(0, invalidations.get(),
                "the losing glob definition must never reach registerResource (it would notify on overwrite)");

        assertTrue(errorsContain("Duplicate id 'reload_test:dup'"),
                "the superseded entry must be recorded: " + Diagnostics.errors(NS));
        assertTrue(errorsContain("data/" + NS + "/state_machines/a.json")
                        && errorsContain("data/" + NS + "/content/b.json"),
                "the conflict must name both source files: " + Diagnostics.errors(NS));
    }

    /** {@link ReloadIndexLoader#indexResourcePath} derives the pack-stack path from the canonical segments. */
    @Test
    void indexResourcePathMatchesTheCanonicalIndexLayout() {
        assertEquals("kasuga_lib/data_driven", ReloadIndexLoader.indexResourcePath("kasuga_lib"));
        assertEquals("kasuga_lib/data_driven", ReloadIndexLoader.indexResourcePath("kuayue"),
                "the index directory is the same for every mod; only the data/<ns> prefix is implicit");
        assertTrue(JsonTreeBuilder.indexDirectorySegments("kasuga_lib")[2].equals("kasuga_lib")
                        && JsonTreeBuilder.indexDirectorySegments("kasuga_lib")[3].equals("data_driven"),
                "the derivation must reuse the shared canonical segments");
    }

    // --- diagnostics ---

    @Test
    void decodeDiagnosticsAreRecordedWithTheFileInTheMessage() {
        orchestrator().reload(manager("state_machines/bad.json", "{ \"state_machines\": {} }"));

        List<Throwable> errors = Diagnostics.errors(NS);
        assertFalse(errors.isEmpty(), "a decode failure must land in the bucket");
        assertTrue(errorsContain("data/" + NS + "/state_machines/bad.json"),
                "the diagnostic must locate the file: " + errors);
    }

    /** Registration content listed in on_reload is reported with the symmetric hint toward on_register. */
    @Test
    void registrationContentListedUnderOnReloadIsHintedAtOnRegister() {
        orchestrator().reload(new TestResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", indexManifest("content/blocks.json"))
                .add(NS, "content/blocks.json", "{ \"blocks\": [] }"));

        assertTrue(errorsContain("unsupported top-level field 'blocks'"),
                "the offending field must be named: " + Diagnostics.errors(NS));
        assertTrue(errorsContain("'on_register'"),
                "the hint must point at the index's other array: " + Diagnostics.errors(NS));
    }

    /** {@code animation_clips} is a recognised reload-domain key; nothing consumes it yet, so it is silent. */
    @Test
    void animationClipsKeyIsRecognisedWithoutAConsumer() {
        orchestrator().reload(new TestResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", indexManifest("content/clips.json"))
                .add(NS, "content/clips.json", "{ \"animation_clips\": [] }"));

        assertEquals(List.of(), Diagnostics.errors(NS),
                "a known reload-domain key must not be reported as an unsupported field");
    }

    /** A manifest path that fails the shared path contract is reported and skipped. */
    @Test
    void invalidIndexPathIsReportedAndSkipped() {
        orchestrator().reload(new TestResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json", indexManifest("../escape.json")));

        assertTrue(errorsContain("Invalid 'on_reload' path '../escape.json'"),
                "the path contract must be enforced on this side too: " + Diagnostics.errors(NS));
    }

    private static boolean errorsContain(String needle) {
        return Diagnostics.errors(NS).stream()
                .anyMatch(error -> error.getMessage() != null && error.getMessage().contains(needle));
    }

    // --- test double ---

    /** In-memory pack stack: a map of virtual files, keyed by resource location. */
    private static final class TestResourceManager implements ResourceManager {

        private final Map<ResourceLocation, String> files = new LinkedHashMap<>();

        TestResourceManager add(String namespace, String path, String content) {
            files.put(ResourceLocation.fromNamespaceAndPath(namespace, path), content);
            return this;
        }

        @Override
        public Set<String> getNamespaces() {
            return files.keySet().stream().map(ResourceLocation::getNamespace)
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            String content = files.get(location);
            return content == null ? Optional.empty() : Optional.of(resource(content));
        }

        @Override
        public List<Resource> getResourceStack(ResourceLocation location) {
            return getResource(location).map(List::of).orElseGet(List::of);
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, Resource> result = new TreeMap<>();
            files.forEach((loc, content) -> {
                if (loc.getPath().startsWith(path + "/") && filter.test(loc)) {
                    result.put(loc, resource(content));
                }
            });
            return result;
        }

        @Override
        public Map<ResourceLocation, List<Resource>> listResourceStacks(String path,
                                                                       Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, List<Resource>> result = new TreeMap<>();
            listResources(path, filter).forEach((loc, resource) -> result.put(loc, List.of(resource)));
            return result;
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.of();
        }

        private static Resource resource(String content) {
            return new Resource(null, () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
