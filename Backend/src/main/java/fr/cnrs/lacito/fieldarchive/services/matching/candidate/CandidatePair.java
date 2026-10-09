package fr.cnrs.lacito.fieldarchive.services.matching.candidate;

import fr.cnrs.lacito.fieldarchive.services.matching.config.TypeGroup;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.EntityProfile;

/** Two profiles to score, {@code a} from the first source of the pair, under {@code group}. */
public record CandidatePair(EntityProfile a, EntityProfile b, TypeGroup group) {

    public String key() {
        return a.iri() + "\u0000" + b.iri();
    }
}
