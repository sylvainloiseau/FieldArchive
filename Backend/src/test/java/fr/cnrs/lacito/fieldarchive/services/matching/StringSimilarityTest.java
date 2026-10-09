package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.services.matching.scoring.ComparableValue;
import fr.cnrs.lacito.fieldarchive.services.matching.scoring.NameComparator;
import fr.cnrs.lacito.fieldarchive.utils.StringSimilarity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Values checked against fuzz.ratio of the notebook (python-Levenshtein), divided by 100. */
class StringSimilarityTest {

    private static double ratio(String a, String b) {
        return StringSimilarity.indelRatio(StringSimilarity.normalize(a), StringSimilarity.normalize(b));
    }

    private static double name(String a, String b) {
        return new NameComparator().compare(ComparableValue.text(a), ComparableValue.text(b), null).getAsDouble();
    }

    @Test
    void indelRatioMatchesTheNotebook() {
        assertThat(ratio("Marie Lefebvre", "Marie Lefevre")).isCloseTo(0.963, within(0.001));
        assertThat(ratio("Dupont, Jean", "Jean Dupont")).isCloseTo(0.545, within(0.001));   // punctuation removed
        assertThat(ratio("Lyon France", "Paris")).isCloseTo(0.125, within(0.001));
        assertThat(ratio("Photographie de l'atelier", "Correspondance personnelle de Paul Foo")).isCloseTo(0.349, within(0.001));
    }

    @Test
    void accentsAndCaseAreIgnored() {
        assertThat(ratio("Musee d'Orsay", "Musée d'Orsay")).isEqualTo(1.0);
        assertThat(ratio("LYON", "lyon")).isEqualTo(1.0);
    }

    @Test
    void nameComparatorIgnoresTokenOrder() {
        assertThat(name("Dupont, Jean", "Jean Dupont")).isEqualTo(1.0);
        assertThat(name("Marie Lefebvre", "Marie Lefevre")).isCloseTo(0.963, within(0.001));
    }

    @Test
    void edgeCases() {
        assertThat(StringSimilarity.indelRatio("", "")).isEqualTo(1.0);
        assertThat(StringSimilarity.indelRatio("abc", "")).isEqualTo(0.0);
        assertThat(StringSimilarity.indelRatio("abc", "xyz")).isEqualTo(0.0);
    }
}
