package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.dtos.matching.MatchingConfigurationDto;
import fr.cnrs.lacito.fieldarchive.dtos.matching.ValidationReportDto;
import fr.cnrs.lacito.fieldarchive.services.matching.config.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class MatchingConfigurationLoaderTest {

    /** An excerpt of ENTITY_EQUIVALENCES from the notebook, unchanged. */
    static final String NOTEBOOK = """
            {
              "prefixes": { "foaf": "http://xmlns.com/foaf/0.1/", "rico": "https://www.ica.org/standards/RiC/ontology#" },
              "note": "Groups of classes considered equivalent",
              "equivalentGroups": [
                { "groupLabel": "Person",
                  "equivalentTypes": ["http://xmlns.com/foaf/0.1/Person", "https://www.ica.org/standards/RiC/ontology#Person"],
                  "properties": {
                    "name": ["http://xmlns.com/foaf/0.1/name", "https://www.ica.org/standards/RiC/ontology#name"],
                    "nickname": ["http://xmlns.com/foaf/0.1/nick"] } },
                { "groupLabel": "Organization / Group / CorporateBody",
                  "equivalentTypes": ["http://xmlns.com/foaf/0.1/Organization"],
                  "properties": { "name": ["http://xmlns.com/foaf/0.1/name"] } }
              ]
            }""";

    @Test
    void theNotebookFormatLoadsUnchanged() {
        MatchingConfigurationDto dto = MatchingConfigurationLoader.parse(NOTEBOOK);
        assertThat(dto.equivalentGroups).extracting(g -> g.id).containsExactly("person", "organization-group-corporatebody");
        assertThat(dto.equivalentGroups.get(0).properties.get("name").comparator).isEqualTo("auto");
        assertThat(dto.equivalentGroups.get(0).properties.get("name").weight).isEqualTo(1.0);
        assertThat(MatchingConfigurationValidator.validate(dto, null).errors).isEmpty();
    }

    @Test
    void curiesAreExpandedOnReadAndCompactedOnWrite() {
        MatchingConfigurationDto dto = MatchingConfigurationLoader.parse("""
                { "prefixes": { "foaf": "http://xmlns.com/foaf/0.1/" },
                  "equivalentGroups": [ { "id": "p", "groupLabel": "P", "equivalentTypes": ["foaf:Person"],
                    "properties": { "name": { "predicates": ["foaf:name", "rdfs:label"], "comparator": "name", "weight": 3 } } } ] }""");
        assertThat(dto.equivalentGroups.get(0).equivalentTypes).containsExactly("http://xmlns.com/foaf/0.1/Person");
        assertThat(dto.equivalentGroups.get(0).properties.get("name").predicates)
                .containsExactly("http://xmlns.com/foaf/0.1/name", "http://www.w3.org/2000/01/rdf-schema#label");
        String written = MatchingConfigurationWriter.toFileJson(dto);
        assertThat(written).contains("\"foaf:name\"").contains("\"foaf:Person\"");
        MatchingConfigurationDto reread = MatchingConfigurationLoader.parse(written);
        assertThat(MatchingConfigurationLoader.hash(reread)).isEqualTo(MatchingConfigurationLoader.hash(dto));
    }

    @Test
    void validationReportsPaths() {
        MatchingConfigurationDto dto = MatchingConfigurationLoader.parse("""
                { "defaults": { "threshold": 1.5 },
                  "equivalentGroups": [ { "id": "Bad Id", "groupLabel": "P", "equivalentTypes": ["nope:Person"],
                    "requiredConcepts": ["missing"],
                    "properties": { "name": { "predicates": ["http://x/name"], "comparator": "fuzzy", "weight": 0 },
                                    "date": { "predicates": ["http://x/d"], "comparator": "date", "options": { "partialOverlapScore": 2 } } } } ] }""");
        ValidationReportDto r = MatchingConfigurationValidator.validate(dto, null);
        assertThat(r.errors).extracting(i -> i.path).contains(
                "defaults.threshold",
                "equivalentGroups[0].id",
                "equivalentGroups[0].equivalentTypes",
                "equivalentGroups[0].requiredConcepts",
                "equivalentGroups[0].properties.name.comparator",
                "equivalentGroups[0].properties.name.weight",
                "equivalentGroups[0].properties.date.options.partialOverlapScore");
    }

    @Test
    void theBundledDefaultIsValid() throws Exception {
        try (var in = getClass().getClassLoader().getResourceAsStream(MatchingConfigurationService.DEFAULT_RESOURCE)) {
            MatchingConfigurationDto dto = MatchingConfigurationLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            ValidationReportDto r = MatchingConfigurationValidator.validate(dto, null);
            assertThat(r.errors).isEmpty();
            assertThat(dto.equivalentGroups).hasSize(8);
        }
    }
}
