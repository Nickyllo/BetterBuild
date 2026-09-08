package dev.nickyllo.betterbuild.core.design;

import dev.nickyllo.betterbuild.core.blueprint.Blueprint;
import dev.nickyllo.betterbuild.core.platform.SiteSurvey;
import java.util.Optional;

/**
 * Everything a design provider gets. Note {@code previous}: a refinement ("raise the
 * roof") carries the design being changed, so the provider edits rather than starts
 * over — cheaper, faster, and it keeps whatever the player already liked.
 */
public record DesignRequest(String prompt, SiteSurvey survey, Optional<Blueprint> previous) {

    public static DesignRequest fresh(String prompt, SiteSurvey survey) {
        return new DesignRequest(prompt, survey, Optional.empty());
    }

    public static DesignRequest refine(String prompt, SiteSurvey survey, Blueprint previous) {
        return new DesignRequest(prompt, survey, Optional.of(previous));
    }

    public boolean isRefinement() {
        return previous.isPresent();
    }
}
