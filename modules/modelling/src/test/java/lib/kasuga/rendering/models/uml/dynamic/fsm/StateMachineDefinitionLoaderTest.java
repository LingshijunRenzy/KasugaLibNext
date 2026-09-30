package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.mc.dynamic.fsm.StateMachineDefinitionLoader;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StateMachineDefinitionLoader#load} against a stub {@link ResourceManager}: definitions
 * land in the injected bucket's RESOURCE section, broken JSON does not interrupt the batch, a
 * second load replaces RESOURCE definitions while SCRIPT definitions survive, and the per-id
 * content hash tracks definition identity.
 *
 * <p>The file-level wrapper contract ({@code {"state_machines": [...]}}) is locked down here too:
 * a file may carry several definitions, an in-file duplicate id is last-wins, a shape violation
 * rejects the whole file, and a malformed array element only drops that element.
 */
class StateMachineDefinitionLoaderTest {

    /** Minimal in-memory resource manager serving the given virtual files. */
    private static final class StubResourceManager implements ResourceManager {
        private final Map<ResourceLocation, String> files = new HashMap<>();

        StubResourceManager add(ResourceLocation loc, String content) {
            files.put(loc, content);
            return this;
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(String path, java.util.function.Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, Resource> result = new HashMap<>();
            files.forEach((loc, content) -> {
                if (loc.getPath().startsWith(path) && filter.test(loc)) {
                    result.put(loc, new Resource(null,
                            () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
                }
            });
            return result;
        }

        @Override
        public Map<ResourceLocation, java.util.List<Resource>> listResourceStacks(String path,
                                                                                  java.util.function.Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, java.util.List<Resource>> result = new HashMap<>();
            listResources(path, filter).forEach((loc, resource) -> result.put(loc, java.util.List.of(resource)));
            return result;
        }

        @Override
        public java.util.Set<String> getNamespaces() {
            return files.keySet().stream().map(ResourceLocation::getNamespace).collect(java.util.stream.Collectors.toSet());
        }

        @Override
        public java.util.List<Resource> getResourceStack(ResourceLocation location) {
            return getResource(location).map(java.util.List::of).orElseGet(java.util.List::of);
        }

        @Override
        public java.util.stream.Stream<net.minecraft.server.packs.PackResources> listPacks() {
            return java.util.stream.Stream.of();
        }

        @Override
        public java.util.Optional<Resource> getResource(ResourceLocation location) {
            String content = files.get(location);
            if (content == null) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new Resource(null,
                    () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))));
        }
    }

    private static final String GOOD_JSON = """
            {
              "state_machines": [
                {
                  "id": "test:good",
                  "layers": [
                    { "id": "l", "mode": "base", "weight": 1.0, "bone_mask": "*",
                      "initial_state": "idle",
                      "states": [ { "id": "idle", "duration_ticks": 10 } ] }
                  ]
                }
              ]
            }
            """;

    private static final String BROKEN_JSON = "{ this is not json }";

    /** A single wrapper file carrying two distinct definitions. */
    private static final String MULTI_JSON = """
            {
              "state_machines": [
                { "id": "test:good", "layers": [ { "id": "l", "initial_state": "idle",
                  "states": [ { "id": "idle", "duration_ticks": 10 } ] } ] },
                { "id": "test:second", "layers": [ { "id": "m", "initial_state": "idle",
                  "states": [ { "id": "idle", "duration_ticks": 20 } ] } ] }
              ]
            }
            """;

    /** The same id twice in one file; the second entry declares a state var, so the winner is identifiable. */
    private static final String DUPLICATE_JSON = """
            {
              "state_machines": [
                { "id": "test:good", "layers": [ { "id": "l", "initial_state": "idle" } ] },
                { "id": "test:good", "state_vars": [ { "name": "x" } ],
                  "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ]
            }
            """;

    /** Pre-wrapper shape: the top-level object is a definition, not a {@code state_machines} array. */
    private static final String LEGACY_JSON = """
            { "id": "test:good", "layers": [ { "id": "l", "initial_state": "idle" } ] }
            """;

    /** A number and a definition missing {@code layers} sit between two good elements. */
    private static final String MIXED_ELEMENTS_JSON = """
            {
              "state_machines": [
                { "id": "test:first", "layers": [ { "id": "l", "initial_state": "idle" } ] },
                5,
                { "id": "test:missing_layers" },
                { "id": "test:last", "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ]
            }
            """;

    private static final String EXTRA_KEY_JSON = """
            {
              "state_machines": [
                { "id": "test:good", "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ],
              "extra": 1
            }
            """;

    private static final String NON_ARRAY_JSON = "{ \"state_machines\": {} }";

    private static final String MISSING_KEY_JSON = "{ \"other\": [] }";

    @Test
    void loadsValidDefinitionsIntoInjectedRegistry() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON);

        loader.load(manager);

        assertNotNull(definitions.get(Id.fromNamespaceAndPath("test", "good")));
    }

    @Test
    void brokenJsonDoesNotAbortTheBatch() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/broken.json"), BROKEN_JSON)
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON);

        loader.load(manager);

        assertNull(definitions.get(Id.fromNamespaceAndPath("test", "broken")));
        assertNotNull(definitions.get(Id.fromNamespaceAndPath("test", "good")));
    }

    @Test
    void reloadReplacesResourceDefinitionsAndKeepsScriptDefinitions() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);

        StubResourceManager first = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON);
        loader.load(first);
        assertNotNull(definitions.get(Id.fromNamespaceAndPath("test", "good")));

        // a script definition on the same registry must survive reloads
        Id scriptId = Id.fromNamespaceAndPath("test", "script_def");
        definitions.register(scriptId, definitions.get(Id.fromNamespaceAndPath("test", "good")));

        // second load with an empty pack: RESOURCE definitions go away, SCRIPT stays
        loader.load(new StubResourceManager());
        assertNull(definitions.get(Id.fromNamespaceAndPath("test", "good")));
        assertNotNull(definitions.get(scriptId));
    }

    @Test
    void hashTracksDefinitionIdentityAcrossReloadAndOverwrite() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        Id good = Id.fromNamespaceAndPath("test", "good");
        assertEquals(0, definitions.hash(good), "absent id hashes to 0");

        loader.load(new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON));
        int loaded = definitions.hash(good);
        assertNotEquals(0, loaded, "a loaded definition has a non-zero content hash");

        // reloading the same content keeps the hash; overwriting with different content changes it
        loader.load(new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON));
        assertEquals(loaded, definitions.hash(good), "same content -> same hash");

        definitions.register(good, new lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition(
                good, java.util.List.of(), java.util.List.of()));
        assertNotEquals(loaded, definitions.hash(good), "different content -> different hash");
    }

    @Test
    void sameJsonCanBeLoadedTwiceWithoutError() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON);
        loader.load(manager);
        loader.load(manager);
        assertNotNull(definitions.get(Id.fromNamespaceAndPath("test", "good")));
    }

    @Test
    void wrapperFileRegistersEveryDefinition() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/pair.json"), MULTI_JSON);

        loader.load(manager);

        assertNotNull(definitions.get(Id.fromNamespaceAndPath("test", "good")),
                "the first wrapper element must load");
        assertNotNull(definitions.get(Id.fromNamespaceAndPath("test", "second")),
                "the second wrapper element must load");
    }

    @Test
    void duplicateIdWithinFileLastWins() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/dup.json"), DUPLICATE_JSON);

        loader.load(manager);

        StateMachineDefinition loaded = definitions.get(Id.fromNamespaceAndPath("test", "good"));
        assertNotNull(loaded, "an id present in the file must be registered");
        assertEquals(1, loaded.stateVars().size(),
                "the later in-file entry wins (the loser declares no state var)");
    }

    @Test
    void legacyShapeIsRejectedAndReportsExpectedShape() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/legacy.json"), LEGACY_JSON);

        loader.load(manager);

        assertNull(definitions.get(Id.fromNamespaceAndPath("test", "good")),
                "a pre-wrapper file must not register anything");

        StateMachineDefinitionLoader.DecodedFile decoded =
                StateMachineDefinitionLoader.decodeFile(JsonParser.parseString(LEGACY_JSON));
        assertTrue(decoded.definitions().isEmpty(), "a pre-wrapper file decodes to no definitions");
        assertTrue(decoded.errors().stream().anyMatch(error -> error.contains("state_machines")),
                "the rejection must name the expected wrapper shape, got " + decoded.errors());
    }

    @Test
    void badElementIsSkippedWhileSiblingsLoad() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/mixed.json"), MIXED_ELEMENTS_JSON);

        loader.load(manager);

        assertNotNull(definitions.get(Id.fromNamespaceAndPath("test", "first")),
                "an element before the bad ones must still load");
        assertNull(definitions.get(Id.fromNamespaceAndPath("test", "missing_layers")),
                "a malformed element must be skipped, not registered");
        assertNotNull(definitions.get(Id.fromNamespaceAndPath("test", "last")),
                "an element after the bad ones must still load");

        StateMachineDefinitionLoader.DecodedFile decoded =
                StateMachineDefinitionLoader.decodeFile(JsonParser.parseString(MIXED_ELEMENTS_JSON));
        assertEquals(2, decoded.definitions().size(), "only the two good elements decode");
        assertTrue(decoded.errors().size() >= 2,
                "each bad element must produce a diagnostic, got " + decoded.errors());
    }

    @Test
    void extraTopLevelKeyRejectsWholeFile() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/extra.json"), EXTRA_KEY_JSON);

        loader.load(manager);

        assertNull(definitions.get(Id.fromNamespaceAndPath("test", "good")),
                "an extra top-level key must reject the whole file");

        StateMachineDefinitionLoader.DecodedFile decoded =
                StateMachineDefinitionLoader.decodeFile(JsonParser.parseString(EXTRA_KEY_JSON));
        assertTrue(decoded.definitions().isEmpty());
        assertFalse(decoded.errors().isEmpty(), "a shape violation must produce a diagnostic");
    }

    @Test
    void nonArrayValueRejectsWholeFile() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/bad.json"), NON_ARRAY_JSON);

        loader.load(manager);

        assertNull(definitions.get(Id.fromNamespaceAndPath("test", "good")),
                "a non-array state_machines value must reject the whole file");
        StateMachineDefinitionLoader.DecodedFile decoded =
                StateMachineDefinitionLoader.decodeFile(JsonParser.parseString(NON_ARRAY_JSON));
        assertTrue(decoded.definitions().isEmpty());
        assertTrue(decoded.errors().stream().anyMatch(error -> error.contains("array")),
                "the diagnostic must mention the array requirement, got " + decoded.errors());
    }

    @Test
    void missingKeyRejectsWholeFile() {
        FsmDefinitions definitions = new FsmDefinitions();
        StateMachineDefinitionLoader loader = new StateMachineDefinitionLoader(definitions);
        StubResourceManager manager = new StubResourceManager()
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/none.json"), MISSING_KEY_JSON)
                .add(ResourceLocation.fromNamespaceAndPath("test", "state_machines/good.json"), GOOD_JSON);

        loader.load(manager);

        assertNotNull(definitions.get(Id.fromNamespaceAndPath("test", "good")),
                "a key-less file must not abort the batch");

        StateMachineDefinitionLoader.DecodedFile decoded =
                StateMachineDefinitionLoader.decodeFile(JsonParser.parseString(MISSING_KEY_JSON));
        assertTrue(decoded.definitions().isEmpty());
        assertTrue(decoded.errors().stream().anyMatch(error -> error.contains("state_machines")),
                "the diagnostic must name the missing key, got " + decoded.errors());
    }
}
