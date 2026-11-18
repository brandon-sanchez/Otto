package otto.board;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import otto.advice.NamedIds;
import otto.decisions.DropRanking;
import otto.directory.DirectoryPlayer;
import otto.snapshot.RosterSnapshot;

/**
 * Holds the board's bids and drops to what code verified: every bid in
 * whole dollars between the league minimum and the most the user can
 * bid, {@code low <= target <= high}, no bid at all when bids are not
 * verified, a legal drop for every claim a full roster cannot take, a
 * drop only from the roster's least valuable players when they could be
 * ranked, and
 * claims placed together that stay affordable together at the top of
 * their ranges and drop different players.
 *
 * <p>Each failure is written for the model, because the correction
 * attempt hands the failures back to it.
 */
final class BidCheck {

    private final BidPolicy policy;
    private final RosterSnapshot user;
    private final int openSpots;
    private final Map<String, DirectoryPlayer> candidates;
    private final DropRanking drops;

    /**
     * @param activeRosterSpots how many players the league lets a team
     *        hold outside IR and the taxi squad
     * @param drops the user's droppable players by value;
     *        {@link DropRanking#UNKNOWN} when the roster could not be
     *        ranked, in which case any legal drop passes
     */
    BidCheck(BidPolicy policy, RosterSnapshot user, int activeRosterSpots,
            Map<String, DirectoryPlayer> candidates, DropRanking drops) {
        this.policy = policy;
        this.user = user;
        this.openSpots = (int) Math.max(0, activeRosterSpots - user.activePlayerCount());
        this.candidates = Map.copyOf(candidates);
        this.drops = drops;
    }

    /** Returns one failure per broken bid or drop rule; empty when the board passes. */
    List<String> check(BoardAnswer answer) {
        List<String> failures = new ArrayList<>();
        if (policy instanceof BidPolicy.Verified verified && !verified.anyLegalBid()
                && !answer.entries().isEmpty()) {
            failures.add(("No legal bid is possible: the minimum bid is $%d and the most the "
                    + "user can bid is $%d. Hold and list no moves.").formatted(
                            verified.minimum(), verified.ceiling()));
            return failures;
        }
        for (BoardAnswer.Entry entry : answer.entries()) {
            checkBid(entry, failures);
            checkDrop(entry, failures);
        }
        checkTogether(answer, failures);
        return failures;
    }

    /**
     * Returns the id of the rostered player a "drop" stands for: the
     * value itself, or the id of the one player on the user's roster
     * whose full name it is exactly. A name nobody or more than one
     * player on the roster has comes back unchanged and fails
     * {@link #check}.
     */
    String rostered(String drop) {
        return NamedIds.resolve(drop, user.playerNames());
    }

    private void checkBid(BoardAnswer.Entry entry, List<String> failures) {
        String name = name(entry.playerId());
        switch (policy) {
            case BidPolicy.NoBids none -> entry.bid().ifPresent(bid -> failures.add(
                    "This league claims by waiver priority, so a claim carries no bid: leave "
                            + "out \"bid\" for %s.".formatted(name)));
            case BidPolicy.Withheld withheld -> entry.bid().ifPresent(bid -> failures.add(
                    "Bids are withheld because %s: leave out \"bid\" for %s."
                            .formatted(withheld.reason(), name)));
            case BidPolicy.Verified verified -> {
                if (entry.bid().isEmpty()) {
                    failures.add(("Give %s a \"bid\" with \"low\", \"target\" and \"high\" in "
                            + "whole dollars.").formatted(name));
                    return;
                }
                checkVerifiedBid(name, entry.bid().get(), verified, failures);
                if (entry.limit().isBlank()) {
                    failures.add("Say in \"limit\" why $%s is the most to bid on %s."
                            .formatted(dollars(entry.bid().get().high()), name));
                } else if (entry.limit().length() > WaiverEvidence.LIMIT_MAX_LENGTH) {
                    failures.add("The limit for %s is %d characters; the limit is %d."
                            .formatted(name, entry.limit().length(),
                                    WaiverEvidence.LIMIT_MAX_LENGTH));
                }
            }
        }
    }

    private static void checkVerifiedBid(String name, BoardAnswer.Bid bid,
            BidPolicy.Verified verified, List<String> failures) {
        if (!whole(bid.low()) || !whole(bid.target()) || !whole(bid.high())) {
            failures.add("Bids move in steps of $%d: give %s's low, target and high in whole "
                    .formatted(BidPolicy.INCREMENT, name) + "dollars.");
            return;
        }
        if (bid.low() > bid.target() || bid.target() > bid.high()) {
            failures.add("%s's bid must keep low <= target <= high; it is $%s, $%s, $%s."
                    .formatted(name, dollars(bid.low()), dollars(bid.target()),
                            dollars(bid.high())));
        }
        if (bid.low() < verified.minimum()) {
            failures.add("%s's low of $%s is below the league's minimum bid of $%d."
                    .formatted(name, dollars(bid.low()), verified.minimum()));
        }
        if (bid.high() > verified.remaining()) {
            failures.add("%s's high of $%s is more than the $%d the user has left."
                    .formatted(name, dollars(bid.high()), verified.remaining()));
        } else if (bid.high() > verified.ceiling()) {
            failures.add("%s's high of $%s is above the user's own limit of $%d on one bid."
                    .formatted(name, dollars(bid.high()), verified.ceiling()));
        }
    }

    private void checkDrop(BoardAnswer.Entry entry, List<String> failures) {
        if (entry.drop().isEmpty()) {
            if (openSpots == 0) {
                failures.add(("The user's roster is full: name in \"drop\" the rostered player "
                        + "%s replaces.").formatted(name(entry.playerId())));
            }
            return;
        }
        String drop = entry.drop().get();
        if (!user.players().contains(drop) || user.reserve().contains(drop)
                || user.taxi().contains(drop)) {
            failures.add(("Player id %s is not on the user's active roster, so he cannot be "
                    + "dropped for %s.").formatted(drop, name(entry.playerId())));
            return;
        }
        DirectoryPlayer pickup = candidates.get(entry.playerId());
        String pickupPosition = pickup == null ? "" : pickup.position();
        if (!drops.allowsDrop(drop, user.playerPositions().get(drop), pickupPosition)) {
            failures.add(("%s is not among the roster's least valuable players, so he is not "
                    + "the one to drop for %s. Drop one of these, by id: %s.").formatted(
                            user.playerNames().getOrDefault(drop, drop), name(entry.playerId()),
                            drops.droppableFor(pickupPosition).stream()
                                    .map(player -> "%s (%s)".formatted(player.fullName(),
                                            player.playerId()))
                                    .collect(Collectors.joining(", "))));
        }
    }

    private void checkTogether(BoardAnswer answer, List<String> failures) {
        if (answer.together().isEmpty()) {
            return;
        }
        if (answer.together().size() < 2) {
            failures.add("Leave \"together\" empty unless two or more picks are meant to land "
                    + "together.");
            return;
        }
        List<String> shown = answer.picks().stream()
                .limit(BoardMessage.SHOWN)
                .map(BoardAnswer.Entry::playerId)
                .toList();
        Set<String> drops = new HashSet<>();
        int withoutDrop = 0;
        int highs = 0;
        for (String playerId : new HashSet<>(answer.together())) {
            Optional<BoardAnswer.Entry> pick = answer.picks().stream()
                    .filter(entry -> entry.playerId().equals(playerId))
                    .findFirst();
            if (pick.isEmpty() || !shown.contains(playerId)) {
                failures.add("Player id %s is in \"together\" but is not one of the first %d "
                        .formatted(playerId, BoardMessage.SHOWN) + "picks.");
                continue;
            }
            BoardAnswer.Entry entry = pick.get();
            if (entry.drop().isEmpty()) {
                withoutDrop++;
            } else if (!drops.add(entry.drop().get())) {
                failures.add("Player id %s is dropped for two claims placed together."
                        .formatted(entry.drop().get()));
            }
            highs += entry.bid().map(bid -> (int) bid.high()).orElse(0);
        }
        if (withoutDrop > openSpots) {
            failures.add(("The claims placed together need %d open roster spots and the user "
                    + "has %d: name a drop for each claim past that.").formatted(withoutDrop,
                            openSpots));
        }
        if (policy instanceof BidPolicy.Verified verified && highs > verified.remaining()) {
            failures.add(("Claims placed together must stay affordable together at the top of "
                    + "every range: their highs add up to $%d, more than the $%d the user has "
                    + "left.").formatted(highs, verified.remaining()));
        }
    }

    private String name(String playerId) {
        DirectoryPlayer player = candidates.get(playerId);
        return player == null ? "player id " + playerId : player.fullName();
    }

    private static boolean whole(double dollars) {
        return dollars == Math.rint(dollars);
    }

    private static String dollars(double amount) {
        return whole(amount) ? String.valueOf((long) amount) : String.valueOf(amount);
    }
}
