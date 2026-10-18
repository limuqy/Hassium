package io.github.limuqy.mc.hassium.compat;

/** Bound once by the owning ServerLevel constructor; never inferred from process state. */
public interface ShadowTickOwner {
    void hassium$setShadowOwner(boolean shadow);
}
