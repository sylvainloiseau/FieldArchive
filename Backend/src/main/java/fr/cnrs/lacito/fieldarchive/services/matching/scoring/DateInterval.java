package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import java.time.LocalDate;

/** A date known to some precision, as the interval of days it may denote. */
public record DateInterval(LocalDate start, LocalDate end, boolean dayPrecise) {

    public boolean overlaps(DateInterval other) {
        return !start.isAfter(other.end) && !other.start.isAfter(end);
    }

    @Override
    public String toString() {
        return dayPrecise ? start.toString() : start + "/" + end;
    }
}
