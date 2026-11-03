package com.conductor.service;

/**
 * Who is asking {@link AssetService} and {@link PublishTargetService} to change a Post's media or destinations
 * while it is frozen (under review or later).
 *
 * <p>The freeze exists so nobody changes what a reviewer is reading. One internal path legitimately must:
 * carrying a slideshow Creative's text edit to the Posts that use it ({@code CreativeEditPropagator}), which
 * re-points them at the re-rendered slides and then reopens the review itself (a new round, or back to the
 * review status) — so the change is never silent. That path passes {@link #CREATIVE_EDIT_PROPAGATION}.
 *
 * <p>This is a method argument, never request data: no controller maps anything onto it, the public attach
 * endpoint and every other caller use the overloads without it (which mean {@link #NONE}), and a frozen Post is
 * refused as before.
 */
public enum FreezeBypass {
    /** The normal rule: a frozen Post's media and destinations are refused. */
    NONE,
    /** A slide-text edit being carried to the Posts that use the Creative. Internal only. */
    CREATIVE_EDIT_PROPAGATION
}
