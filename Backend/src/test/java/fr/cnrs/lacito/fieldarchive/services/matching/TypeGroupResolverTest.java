package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfiguration;
import fr.cnrs.lacito.fieldarchive.services.matching.config.MatchingConfigurationLoader;
import fr.cnrs.lacito.fieldarchive.services.matching.config.TypeGroup;
import fr.cnrs.lacito.fieldarchive.services.matching.profile.TypeGroupResolver;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TypeGroupResolverTest {

    static final String RICO = "https://www.ica.org/standards/RiC/ontology#";
    static final String FOAF = "http://xmlns.com/foaf/0.1/";

    static final Map<String, Set<String>> SUBCLASSES = Map.of(
            RICO + "Agent", Set.of(RICO + "Agent", RICO + "Person", RICO + "CorporateBody", RICO + "Group"),
            FOAF + "Agent", Set.of(FOAF + "Agent", FOAF + "Person", FOAF + "Organization", FOAF + "Group"));

    static TypeGroupResolver resolver(boolean compareUntyped) {
        MatchingConfiguration c = MatchingConfigurationLoader.compile(MatchingConfigurationLoader.parse("""
                { "defaults": { "compareUntypedEntities": %s },
                  "equivalentGroups": [
                    { "groupLabel": "Person", "equivalentTypes": ["foaf:Person", "rico:Person"], "properties": { "name": ["foaf:name"] } },
                    { "groupLabel": "Agent", "equivalentTypes": ["foaf:Agent", "rico:Agent"], "properties": { "name": ["foaf:name"] } },
                    { "groupLabel": "Organization", "equivalentTypes": ["foaf:Organization", "rico:CorporateBody"], "properties": { "name": ["foaf:name"] } },
                    { "groupLabel": "Place", "equivalentTypes": ["rico:Place"], "properties": { "name": ["rico:name"] } }
                  ] }""".formatted(compareUntyped)));
        return new TypeGroupResolver(c, t -> SUBCLASSES.getOrDefault(t, Set.of(t)));
    }

    private static Optional<String> shared(TypeGroupResolver r, Set<String> a, Set<String> b) {
        Optional<String> ab = r.sharedGroup(a, b).map(TypeGroup::id);
        assertThat(r.sharedGroup(b, a).map(TypeGroup::id)).isEqualTo(ab);   // symmetric
        return ab;
    }

    @Test
    void thePlanTable() {
        TypeGroupResolver r = resolver(false);
        assertThat(shared(r, Set.of(RICO + "Person"), Set.of(FOAF + "Person"))).contains("person");
        assertThat(shared(r, Set.of(RICO + "Person"), Set.of(RICO + "Agent"))).contains("agent");
        assertThat(shared(r, Set.of(RICO + "Person"), Set.of(FOAF + "Organization"))).isEmpty();
        assertThat(shared(r, Set.of(RICO + "Person", RICO + "Place"), Set.of(RICO + "Place"))).contains("place");
    }

    @Test
    void anEntityWithAnExtraTypeStillBelongsToItsGroup() {   // notebook issue A4
        TypeGroupResolver r = resolver(false);
        assertThat(shared(r, Set.of(RICO + "Person", FOAF + "Agent"), Set.of(FOAF + "Person"))).contains("person");
        assertThat(shared(r, Set.of(RICO + "Person", "http://example.org/Other"), Set.of(FOAF + "Person"))).contains("person");
    }

    @Test
    void untypedEntitiesFollowTheOption() {                  // notebook issue A3
        assertThat(shared(resolver(false), Set.of(), Set.of(FOAF + "Person"))).isEmpty();
        assertThat(shared(resolver(true), Set.of(), Set.of(FOAF + "Person"))).contains("person");
        assertThat(shared(resolver(true), Set.of(), Set.of())).isEmpty();
    }
}
