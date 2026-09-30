package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.data.DataProvider;
import org.jetbrains.annotations.Nullable;

/**
 * Root of the model-rendering "element proxy" scaffolding: adapts a Minecraft element (block/item)
 * plus its world instance into the UML layer's {@link DataProvider}.
 *
 * <p><b>Unwired scaffolding.</b> The whole {@code mc.proxies} family — this interface, its
 * implementations and the {@code reflect} helpers — has zero construction sites and zero references
 * outside this package across the repository. It is kept in place pending a design pass; it is not
 * part of any live render pipeline.
 *
 * @param <ProxiedType>         the Minecraft element type (e.g. {@code Block} / {@code Item})
 * @param <ProxiedInstanceType> the element's world instance type (e.g. {@code BlockState} / {@code ItemStack})
 */
public interface ElementProxy<ProxiedType, ProxiedInstanceType> {

    boolean isValidInput(Object input);

    boolean isValidInstance(Object instance);

    @Nullable DataProvider getDataProvider(ProxiedInstanceType instance, Object... externalData);
}
