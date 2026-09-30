package lib.kasuga.rendering.models.mc.proxies.reflect;

/**
 * Access policy for a {@link FieldHolder} probed by {@link InstanceProbe}.
 *
 * <p><b>Unwired scaffolding</b> — see {@link lib.kasuga.rendering.models.mc.proxies.ElementProxy}:
 * the whole {@code mc.proxies} family, this {@code reflect} helper included, has no construction
 * site and no reference outside the package. Retained pending a design pass.
 */
public interface Limitations {

    boolean canSet(FieldHolder holder, Object value);

    boolean canGet(FieldHolder holder);

    Object getValue(FieldHolder holder, Object gotValue);

    Object setValue(FieldHolder holder, Object originalValue, Object newValue);
}
