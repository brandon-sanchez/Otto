package otto.trade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

import otto.advice.Ordinal;
import otto.league.Seeding;
import otto.market.DraftPick;
import otto.sleeper.SleeperAdapter;
import otto.snapshot.RosterSnapshot;

/**
 * Who holds which future rookie pick, from Sleeper's {@code traded_picks}
 * list, and where in its round each pick should fall.
 *
 * <p>A pick falls where the team it was issued to finishes, so a pick
 * in next year's draft is placed early, mid or late in its round from
 * that team's place in the standings today: the bottom third picks
 * early and the top third late. Picks further out, and any pick before
 * a game has been played, carry no place, because the standings say
 * nothing about them yet.
 *
 * @param seeded every team, best record first
 * @param nextDraft the year of the next rookie draft, empty when the
 *        season is unknown
 */
record HeldPicks(List<SleeperAdapter.TradedPick> traded, List<RosterSnapshot> seeded,
        OptionalInt nextDraft) {

    HeldPicks {
        traded = List.copyOf(traded);
        seeded = List.copyOf(seeded);
    }

    static HeldPicks of(List<SleeperAdapter.TradedPick> traded, List<RosterSnapshot> rosters,
            OptionalInt nextDraft) {
        return new HeldPicks(traded,
                rosters.stream().sorted(Seeding.BY_SEED).toList(), nextDraft);
    }

    /**
     * One pick a trade names, matched to a pick the giving team holds.
     *
     * @param pick the pick as the market prices it, with its place in the
     *        round when the standings give one
     * @param held false when the giving team holds no pick of that year
     *        and round, in which case its own pick is priced anyway
     * @param notes what the reader needs to know about whose pick it is
     */
    record Held(DraftPick pick, boolean held, List<String> notes) {
    }

    /**
     * Finds the pick of the named year and round that the giving team
     * holds. A pick the user placed in the round picks the held pick that
     * falls there; otherwise the team's own pick wins over one it got in a
     * trade.
     */
    Held resolve(DraftPick named, RosterSnapshot giver) {
        boolean yours = giver.userRoster();
        String giverName = yours ? "you" : giver.manager();
        List<RosterSnapshot> issuedTo = seeded.stream()
                .filter(original -> holder(named, original) == giver.rosterId())
                .toList();
        List<String> notes = new ArrayList<>();
        if (issuedTo.isEmpty()) {
            RosterSnapshot holder = rosterById(holder(named, giver)).orElse(giver);
            notes.add("%s %s a %s. %s own went to %s, so this isn't a pick %s can trade"
                    .formatted(capitalized(giverName), yours ? "don't hold" : "doesn't hold",
                            roundWords(named), yours ? "Your" : giverName + "'s",
                            holder.manager(), giverName));
            return new Held(placed(named, giver), false, notes);
        }
        RosterSnapshot original = issuedTo.stream()
                .filter(team -> named.slot().isPresent()
                        && slotOf(named.year(), team).equals(named.slot()))
                .findFirst()
                .or(() -> issuedTo.stream()
                        .filter(team -> team.rosterId() == giver.rosterId())
                        .findFirst())
                .orElse(issuedTo.getFirst());
        DraftPick priced = named.slot().isPresent() ? named : placed(named, original);
        if (original.rosterId() != giver.rosterId()) {
            notes.add("This is %s's pick, which %s got in a trade".formatted(original.manager(),
                    giverName));
        }
        if (issuedTo.size() > 1 && named.slot().isEmpty()) {
            notes.add("%s %s %d %ss, and I priced the one from %s. Say early, mid or late if "
                    .formatted(capitalized(giverName), yours ? "hold" : "holds",
                            issuedTo.size(), roundWords(named), original.manager())
                    + "you mean another");
        }
        String standing = original.rosterId() == giver.rosterId() && yours
                ? "You are" : original.manager() + " is";
        slotOf(named.year(), original).filter(slot -> named.slot().isEmpty())
                .ifPresent(slot -> notes.add(("%s %s of %d in the standings, so the pick "
                        + "should fall %s in the round").formatted(standing,
                        Ordinal.of(seeded.indexOf(original) + 1), seeded.size(),
                        slot.name().toLowerCase(Locale.ROOT))));
        return new Held(priced, true, notes);
    }

    /**
     * Every pick the team holds in the first {@code rounds} rounds of the
     * next {@code drafts} drafts, its own and those it got in trades,
     * each placed in its round when the standings say where. Two picks
     * that read the same, such as two unplaced 2028 1sts, are listed
     * once, because a trade could not tell them apart by name.
     *
     * @return empty when the season, and so the next draft, is unknown
     */
    List<DraftPick> heldBy(RosterSnapshot team, int rounds, int drafts) {
        if (nextDraft.isEmpty()) {
            return List.of();
        }
        List<DraftPick> held = new ArrayList<>();
        for (int year = nextDraft.getAsInt(); year < nextDraft.getAsInt() + drafts; year++) {
            for (int round = 1; round <= rounds; round++) {
                DraftPick unplaced = new DraftPick(year, round, Optional.empty());
                seeded.stream()
                        .filter(original -> holder(unplaced, original) == team.rosterId())
                        .sorted(ownPickFirst(team))
                        .map(original -> placed(unplaced, original))
                        .filter(pick -> held.stream()
                                .noneMatch(other -> other.describe().equals(pick.describe())))
                        .forEach(held::add);
            }
        }
        return held;
    }

    /**
     * Orders the team's own pick before picks it got in trades, the pick
     * {@link #resolve} chooses when a pick is named without its place.
     */
    private static Comparator<RosterSnapshot> ownPickFirst(RosterSnapshot team) {
        return Comparator.comparing(original -> original.rosterId() != team.rosterId());
    }

    private DraftPick placed(DraftPick named, RosterSnapshot original) {
        return new DraftPick(named.year(), named.round(), slotOf(named.year(), original));
    }

    private int holder(DraftPick pick, RosterSnapshot original) {
        String season = String.valueOf(pick.year());
        return traded.stream()
                .filter(moved -> moved.season().equals(season) && moved.round() == pick.round()
                        && moved.originalRosterId() == original.rosterId())
                .map(SleeperAdapter.TradedPick::ownerRosterId)
                .findFirst()
                .orElse(original.rosterId());
    }

    private Optional<DraftPick.Slot> slotOf(int year, RosterSnapshot original) {
        boolean played = seeded.stream().anyMatch(team -> team.teamRecord().games() > 0);
        if (nextDraft.isEmpty() || nextDraft.getAsInt() != year || !played) {
            return Optional.empty();
        }
        int place = seeded.indexOf(original);
        int third = seeded.size() / 3;
        if (place < third) {
            return Optional.of(DraftPick.Slot.LATE);
        }
        return Optional.of(place >= seeded.size() - third ? DraftPick.Slot.EARLY
                : DraftPick.Slot.MID);
    }

    private Optional<RosterSnapshot> rosterById(int rosterId) {
        return seeded.stream().filter(team -> team.rosterId() == rosterId).findFirst();
    }

    private static String roundWords(DraftPick pick) {
        return "%d %s".formatted(pick.year(), Ordinal.of(pick.round()));
    }

    private static String capitalized(String name) {
        return name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
