package lib.kasuga.rendering.models.mc.proxies;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;

import java.util.Set;
import java.util.stream.Stream;

/**
 * Membership test over an element's tags, for the element-proxy layer.
 *
 * <p><b>Unwired scaffolding</b> — see {@link ElementProxy}: no construction site and no reference
 * outside the {@code mc.proxies} package. Retained pending a design pass.
 */
public class TagProvider {

    protected final Set<String> tags;

    public TagProvider(Stream<TagKey<?>> tags) {
        this.tags = tags.map(TagKey::location).map(ResourceLocation::toString).collect(java.util.stream.Collectors.toSet());
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }
}
