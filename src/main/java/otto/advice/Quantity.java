package otto.advice;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A number or a rank that a text states. {@link AdviceCheck} holds every
 * quantity the advice writes to the quantities its evidence states, and
 * a quantity backs only an equal one of the same type and kind, so a
 * plain number never backs a rank and a rank from the bottom never backs
 * one from the top.
 */
sealed interface Quantity permits Quantity.Amount, Ordinal.Place {

    /** A number that is not a dollar amount, part of a word, or a rank. */
    Pattern AMOUNT = Pattern.compile("(?<![\\w.$])\\d+(?:\\.\\d+)?(?!\\w)");

    /** A plain number, held by value, so "12.0" and "12" are one amount. */
    record Amount(BigDecimal value) implements Quantity {

        public Amount {
            value = value.stripTrailingZeros();
        }
    }

    /**
     * One quantity as a text writes it.
     *
     * @param text the quantity as written, for a failure message
     * @param standing true for a rank written with its population, as in
     *        "9th of 12"
     */
    record Stated(String text, Quantity quantity, boolean standing) {

        /** Returns the quantity for a failure message, such as "number 7" or "rank 3rd". */
        String describe() {
            return switch (quantity) {
                case Amount _ -> "number " + text;
                case Ordinal.Place _ -> "rank " + text;
            };
        }
    }

    /**
     * Returns every quantity in the text, ranks first. A rank's digits are
     * never also read as a plain number.
     */
    static List<Stated> in(String text) {
        List<Stated> found = new ArrayList<>();
        StringBuilder rest = new StringBuilder(text);
        for (Ordinal.Written written : Ordinal.in(text)) {
            found.add(new Stated(written.text(), written.place(), written.ofPopulation()));
            for (int at = written.start(); at < written.end(); at++) {
                rest.setCharAt(at, ' ');
            }
        }
        Matcher amount = AMOUNT.matcher(rest);
        while (amount.find()) {
            found.add(new Stated(amount.group(), new Amount(new BigDecimal(amount.group())),
                    false));
        }
        return found;
    }

    /** Returns the plain numbers in the texts; no rank counts from free text. */
    static Set<Quantity> amountsOf(Stream<String> texts) {
        return texts.flatMap(text -> in(text).stream())
                .map(Stated::quantity)
                .filter(Amount.class::isInstance)
                .collect(Collectors.toSet());
    }

    /**
     * Returns every quantity the items state. A rank counts only from an
     * item's {@link Ordinal#RANK_FACT} or from a standing written with its
     * population, such as "9th of 12", and a rank from the bottom only
     * from its {@link Ordinal#FROM_BOTTOM_FACT}, so an ordinal in a
     * description such as "1st is the best defense" supports nothing. A
     * draft round or pick counts only from {@link Ordinal#DRAFT_FACT},
     * and its overall pick, such as "10th overall", also backs the plain
     * pick number, so advice may write "pick 10".
     */
    static Set<Quantity> of(Stream<EvidenceItem> items) {
        return items.flatMap(item -> item.facts().entrySet().stream())
                .flatMap(fact -> in(fact.getValue()).stream()
                        .filter(stated -> stated(fact.getKey(), stated))
                        .flatMap(Quantity::backs))
                .collect(Collectors.toSet());
    }

    private static Stream<Quantity> backs(Stated stated) {
        if (stated.quantity() instanceof Ordinal.Place place
                && place.kind() == Ordinal.Kind.DRAFT
                && stated.text().toLowerCase(Locale.ROOT).endsWith("overall")) {
            return Stream.of(place, new Amount(BigDecimal.valueOf(place.rank())));
        }
        return Stream.of(stated.quantity());
    }

    private static boolean stated(String fact, Stated stated) {
        return switch (stated.quantity()) {
            case Amount _ -> true;
            case Ordinal.Place place -> switch (place.kind()) {
                case RANK -> stated.standing() || fact.equals(Ordinal.RANK_FACT);
                case FROM_BOTTOM -> fact.equals(Ordinal.FROM_BOTTOM_FACT);
                case DRAFT -> fact.equals(Ordinal.DRAFT_FACT);
            };
        };
    }
}
