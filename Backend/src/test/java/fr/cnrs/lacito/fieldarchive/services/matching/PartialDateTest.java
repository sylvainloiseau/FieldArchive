package fr.cnrs.lacito.fieldarchive.services.matching;

import fr.cnrs.lacito.fieldarchive.services.matching.scoring.*;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

class PartialDateTest {

    private static DateInterval parse(String s) {
        return PartialDate.parse(s).orElseThrow();
    }

    @Test
    void parsesTheXsdForms() {
        assertThat(parse("1975")).isEqualTo(new DateInterval(LocalDate.of(1975, 1, 1), LocalDate.of(1975, 12, 31), false));
        assertThat(parse("1975-02")).isEqualTo(new DateInterval(LocalDate.of(1975, 2, 1), LocalDate.of(1975, 2, 28), false));
        assertThat(parse("1975-03-12")).isEqualTo(new DateInterval(LocalDate.of(1975, 3, 12), LocalDate.of(1975, 3, 12), true));
        assertThat(parse("1975-03-12T10:00:00Z").dayPrecise()).isTrue();
        assertThat(parse("1975Z").start()).isEqualTo(LocalDate.of(1975, 1, 1));
        assertThat(parse("1975-03-12+01:00").start()).isEqualTo(LocalDate.of(1975, 3, 12));
    }

    @Test
    void parsesIntervals() {
        DateInterval d = parse("1950/1955-06");
        assertThat(d.start()).isEqualTo(LocalDate.of(1950, 1, 1));
        assertThat(d.end()).isEqualTo(LocalDate.of(1955, 6, 30));
        assertThat(PartialDate.parse("1960/1950")).isEmpty();
    }

    @Test
    void rejectsAnythingElse() {
        assertThat(PartialDate.parse("ca. 1950")).isEmpty();
        assertThat(PartialDate.parse("1975-13")).isEmpty();
        assertThat(PartialDate.parse("1975-02-30")).isEmpty();
        assertThat(PartialDate.parse("")).isEmpty();
    }

    @Test
    void dateComparatorFollowsTheNotebook() {
        DateComparator c = new DateComparator();
        ComparisonContext ctx = new ComparisonContext(null, null);
        assertThat(score(c, "1975-03-12", "1975-03-12", ctx)).isEqualTo(1.0);
        assertThat(score(c, "1975", "1975-03-12", ctx)).isEqualTo(0.85);
        assertThat(score(c, "1975", "1975", ctx)).isEqualTo(0.85);
        assertThat(score(c, "1975", "1990", ctx)).isEqualTo(0.0);
    }

    private static double score(ValueComparator c, String a, String b, ComparisonContext ctx) {
        OptionalDouble s = c.compare(ComparableValue.date(parse(a), a), ComparableValue.date(parse(b), b), ctx);
        return s.getAsDouble();
    }
}
