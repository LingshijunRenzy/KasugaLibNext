package lib.kasuga.rendering.models.mc.dynamic.fsm;

import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the exception boundary of the reload orchestrator: no failure of any stage may escape
 * {@link ReloadIndexLoader#reload(ResourceManager)} — a throw there would abort the pack reload with
 * the buckets already cleared — and an isolated failure must not stop the rest of the cycle.
 *
 * <p>Kept apart from {@code ReloadIndexLoaderTest} so that class's happy-path, discovery-order and
 * diagnostic assertions stay exactly as they are; this class reuses the same
 * {@link StubResourceManager} pack stack and is a plain JVM test (no mod jar, no running game).
 */
class ReloadIndexLoaderExceptionBoundaryTest {

    private static final String NS = "reload_test";

    private static final String GOOD_JSON = """
            {
              "state_machines": [
                { "id": "reload_test:good",
                  "layers": [ { "id": "l", "initial_state": "idle",
                    "states": [ { "id": "idle", "duration_ticks": 10 } ] } ] }
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

    /**
     * An {@code on_reload} entry whose path is not a resource location — an uppercase letter is outside
     * the path character set, while the shared path contract (relative, {@code .json}, no {@code ..})
     * accepts it — must be rejected as one bad entry, not thrown out of {@code ResourceLocation}. The
     * cycle completes and the manifest's later entries still load.
     */
    @Test
    void uppercaseIndexPathIsRejectedAndTheCycleContinues() {
        orchestrator().reload(new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json",
                        "{ \"on_reload\": [ \"content/Blocks.json\", \"content/good.json\" ] }")
                .add(NS, "content/good.json", GOOD_JSON));

        assertTrue(errorsContain("Invalid 'on_reload' path 'content/Blocks.json'"),
                "the illegal entry must be reported, not thrown: " + Diagnostics.errors(NS));
        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")),
                "an entry rejected for its path must not stop the entries after it");
    }

    /**
     * A content file whose read throws a {@link RuntimeException} (not an {@link java.io.IOException},
     * so it is not the already-handled read failure) is isolated per file: it is reported from
     * {@code reload()} without escaping, and the file listed after it in the same manifest still
     * registers.
     */
    @Test
    void throwingFileIsIsolatedAndTheNextFileStillLoads() {
        StubResourceManager pack = new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json",
                        "{ \"on_reload\": [ \"content/poisoned.json\", \"content/good.json\" ] }")
                .add(NS, "content/good.json", GOOD_JSON);

        orchestrator().reload(poisoning(pack, NS, "content/poisoned.json"));

        assertTrue(errorsContain("data/" + NS + "/content/poisoned.json"),
                "the failing file must be reported: " + Diagnostics.errors(NS));
        assertNotNull(definitions.get(Id.fromNamespaceAndPath(NS, "good")),
                "a file that throws must not stop the files after it");
    }

    /**
     * Wraps a stub pack stack so that one path yields a file whose read throws: the stream supplier is
     * only invoked by {@link Resource#openAsReader()}, so the failure happens inside the per-file guard
     * and not while the pack stack itself is queried.
     */
    private static ResourceManager poisoning(StubResourceManager delegate, String namespace, String path) {
        ResourceLocation poisoned = ResourceLocation.fromNamespaceAndPath(namespace, path);
        return new ResourceManager() {
            @Override
            public Set<String> getNamespaces() {
                return delegate.getNamespaces();
            }

            @Override
            public Optional<Resource> getResource(ResourceLocation location) {
                if (location.equals(poisoned)) {
                    return Optional.of(new Resource(null, () -> {
                        throw new IllegalStateException("poisoned content file");
                    }));
                }
                return delegate.getResource(location);
            }

            @Override
            public List<Resource> getResourceStack(ResourceLocation location) {
                return delegate.getResourceStack(location);
            }

            @Override
            public Map<ResourceLocation, Resource> listResources(String directory,
                                                                 Predicate<ResourceLocation> filter) {
                return delegate.listResources(directory, filter);
            }

            @Override
            public Map<ResourceLocation, List<Resource>> listResourceStacks(String directory,
                                                                            Predicate<ResourceLocation> filter) {
                return delegate.listResourceStacks(directory, filter);
            }

            @Override
            public Stream<PackResources> listPacks() {
                return delegate.listPacks();
            }
        };
    }

    private static boolean errorsContain(String needle) {
        return Diagnostics.errors(NS).stream()
                .anyMatch(error -> error.getMessage() != null && error.getMessage().contains(needle));
    }
}
