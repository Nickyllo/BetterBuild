package dev.nickyllo.betterbuild.core.design;

import dev.nickyllo.betterbuild.core.blueprint.Blueprint;
import dev.nickyllo.betterbuild.core.learn.StyleProfile;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;

import java.util.Optional;

/**
 * Everything a design provider gets.
 *
 * <p>Two optional fields carry the memory. {@code previous} makes a refinement
 * ("raise the roof") an edit of the design being changed rather than a fresh start.
 * {@code style} is what the Architect learned from a building the player made, so a
 * new house comes out looking like it belongs beside the old one.
 */
public record DesignRequest(String prompt, SiteSurvey survey, Optional<Blueprint> previous,
                            Optional<StyleProfile> style) {

    public static DesignRequest fresh(String prompt, SiteSurvey survey) {
        return new DesignRequest(prompt, survey, Optional.empty(), Optional.empty());
    }

    public static DesignRequest refine(String prompt, SiteSurvey survey, Blueprint previous) {
        return new DesignRequest(prompt, survey, Optional.of(previous), Optional.empty());
    }

    /** A commission to be built in a style learned from somewhere in the world. */
    public static DesignRequest inStyle(String prompt, SiteSurvey survey, StyleProfile style) {
        return new DesignRequest(prompt, survey, Optional.empty(), Optional.of(style));
    }

    public boolean isRefinement() {
        return previous.isPresent();
    }

    public boolean hasLearnedStyle() {
        return style.isPresent();
    }
}
