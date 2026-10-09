package fr.cnrs.lacito.fieldarchive.services.matching.scoring;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the date forms met in the data into a {@link DateInterval}: {@code YYYY},
 * {@code YYYY-MM}, {@code YYYY-MM-DD} (the lexical forms of xsd:gYear, xsd:gYearMonth,
 * xsd:date), an xsd:dateTime (its date part), each with an optional timezone (ignored), and an
 * ISO/EDTF interval {@code start/end}. Anything else is not a date.
 */
public final class PartialDate {

    private static final Pattern YEAR = Pattern.compile("^(\\d{4})$");
    private static final Pattern YEAR_MONTH = Pattern.compile("^(\\d{4})-(\\d{2})$");
    private static final Pattern DAY = Pattern.compile("^(\\d{4})-(\\d{2})-(\\d{2})$");
    private static final Pattern DATE_TIME = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})T.*$");
    private static final Pattern TIMEZONE = Pattern.compile("(?<=\\d)(Z|[+-]\\d{2}:\\d{2})$");

    private PartialDate() {}

    public static Optional<DateInterval> parse(String value) {
        if (value == null) return Optional.empty();
        String s = value.trim();
        if (s.isEmpty()) return Optional.empty();
        int slash = s.indexOf('/');
        if (slash > 0) {
            Optional<DateInterval> from = parseSingle(s.substring(0, slash));
            Optional<DateInterval> to = parseSingle(s.substring(slash + 1));
            if (from.isEmpty() || to.isEmpty() || from.get().start().isAfter(to.get().end())) return Optional.empty();
            boolean exact = from.get().dayPrecise() && from.get().equals(to.get());
            return Optional.of(new DateInterval(from.get().start(), to.get().end(), exact));
        }
        return parseSingle(s);
    }

    private static Optional<DateInterval> parseSingle(String raw) {
        String s = raw.trim();
        Matcher dt = DATE_TIME.matcher(s);
        if (dt.matches()) s = dt.group(1);
        s = TIMEZONE.matcher(s).replaceFirst("");
        try {
            Matcher m = DAY.matcher(s);
            if (m.matches()) {
                LocalDate d = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                return Optional.of(new DateInterval(d, d, true));
            }
            m = YEAR_MONTH.matcher(s);
            if (m.matches()) {
                YearMonth ym = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                return Optional.of(new DateInterval(ym.atDay(1), ym.atEndOfMonth(), false));
            }
            m = YEAR.matcher(s);
            if (m.matches()) {
                int y = Integer.parseInt(m.group(1));
                return Optional.of(new DateInterval(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), false));
            }
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
