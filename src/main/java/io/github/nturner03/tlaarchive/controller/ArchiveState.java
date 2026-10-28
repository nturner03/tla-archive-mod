package io.github.nturner03.tlaarchive.controller;

/** What a controller's counts mean right now (owner rulings 1 and 7d; {@code docs/architecture.md} → States). */
public enum ArchiveState {
    /** The node is active and the counts come from a complete reading of the grid it is on now. */
    LIVE,
    /**
     * Last known counts, kept and labelled with their time: the node is inactive or unpowered, the controller was
     * unloaded, or the grid or manifest changed and has not been read again yet.
     */
    STALE,
    /** No reading has ever completed. */
    UNAVAILABLE,
    /** The owning team was deleted. Counts stop updating; a former member re-places the controller to claim it. */
    FROZEN
}
