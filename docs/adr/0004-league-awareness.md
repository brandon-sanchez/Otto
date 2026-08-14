# ADR-0004: League awareness

Status: accepted (2026-08-08, issue #15)

Amended (2026-08-13, issue #39): the transaction status vocabulary and
the `commissioner` transaction type are settled below. Everything #15
decided stands.

The spec pins the three league tools, the informational Alert triggers,
the Notable Player cutoffs and the confidence gate. It leaves the
semantics of each open. This ADR records what implementation decided.

## League activity is read from the transactions feed, not from roster arithmetic

Two consecutive Snapshots can show that a player left one roster and
joined another, but they cannot say why. A trade and a same-minute drop
followed by a free agent pickup look identical in roster state, and the
week a waiver period clears is exactly when both happen. Calling one the
other would put a wrong sentence in the user's pocket.

Sleeper's `transactions/{week}` endpoint labels each move - trade,
waiver, free agent, commissioner - and names both sides. It is a
documented v1 endpoint and the spec already lists it as a source, so the
Check reads it and hands it to the Snapshot Diff. A drop is then a
player in a transaction's `drops` who is not in its `adds`: a trade
moves every player it names, so a trade drops nobody.

The Check reads the week before the current one as well. Sleeper files a
transaction under the week it happened in, and the week rolls over on a
Tuesday, so a trade agreed in the closing minutes of a week would drop
out of view the moment the week advanced - and stay out of view for the
rest of the season. Reading both weeks keeps it in view for as long as
it is still news, and costs one conditional GET.

## Only a completed transaction is news, and the vocabulary is stated

Sleeper's own code names six transaction statuses: `proposed`,
`pending`, `cancelled`, `failed`, `complete` and `rejected`. A scan of
284,671 live rows across 6,250 leagues in 2026 saw only `complete` and
`failed` on `transactions/{week}`. The others exist inside the product
and are not served there.

`pending` is the one worth naming. Sleeper's app shows a trade that has
been accepted and is inside the commissioner review window; the
documented endpoint does not publish that state, and the scan confirms
it - 20% of completed trades in a subsample sat more than a day between
`created` and `status_updated`, so review windows were open while the
scan ran and not one `pending` row appeared. An Alert that waits for
that state would never fire, and the internal endpoint that serves it is
undocumented and unversioned, which is the thing the spec ruled out when
it ruled out scraping.

`TransactionStatus` therefore holds the whole vocabulary, marks
`complete` as the only accepted one, and states on each of the others
why it is refused. This used to be an inline comparison against the
string `complete`, which was right by the way the line was written
rather than by a stated rule - and the difference matters the day
Sleeper starts publishing a status the line has never seen.

A status outside the vocabulary is dropped and logged with its
transaction id. Reading an unknown status as news would put a move that
may never have happened in the user's pocket; dropping it in silence
would leave a Sleeper change nobody could find. It is one row, not the
whole read, so the week's real activity still arrives: this is not
schema drift, where the shape of the feed has stopped being readable.

## A Commissioner Edit is a trade's fact in different words

`commissioner` is the fourth transaction type - undocumented, and 6,377
rows of it observed live. It is how a roster changes when nobody on it
agreed to anything, and a player leaving the user's roster that way is
as much news as any trade.

A player who appears in both a transaction's adds and its drops crossed
from one roster to another. A Commissioner Edit that does that produces
the same per-player events a trade does; only the kind differs, and
with it the sentence. One that adds a player from free agency, or cuts
one to it, is already an add or a drop and rides with the rest.

The message says "Commissioner edit", not "League trade", because
calling it a trade would name an agreement that never happened. It
rides at High for the same reason a trade does: it is a fact, and it is
already done. It carries the same trigger and the same mute class as a
trade - the user who asked to hear about players changing hands meant
this too, and a second class to mute would be vocabulary he never asked
for.

A Commissioner Edit elsewhere in the league sends whoever it touched,
the way a trade between two other managers does, rather than waiting on
the Notable Player cutoffs. The cutoffs answer "is this man worth a
claim"; nobody can claim a player who is already on another roster, and
the news here is that the league's rules moved him.

## A cut the commissioner made is not a cut the user made

A player can also leave the user's roster with nobody gaining him.
That is a drop, and drops on the user's own roster are filtered out as
his own decision - which is true when he made it and false when the
commissioner did, and the two are identical in roster state.

So every event a Commissioner Edit produces is stamped as one, and the
filter reads the stamp. His own cut still stays quiet; the
commissioner's cut sends at High, states the loss and names the slot it
opened. It does not go near the Notable cutoffs: he is short a player
he chose to hold, whatever the projection table thinks of the man.

The fifth type, `chopped`, was not observed live and gets no words of
its own. It needs none: a type this code does not name is read by what
it did to the rosters - an add, a drop - and never earns a sentence
that names an actor nobody has seen act.

## The Event Log keeps the facts; the detector keeps the words

A trade arrives as one diff event per player it moved, each naming the
player, the manager who gave him up and the manager who got him. The
detector groups the events by transaction id and writes the one message
a trade deserves.

The other way round - the Snapshot Diff storing a rendered sentence -
would put prose in a season-long record that the rest of the system has
to query. "Who did this manager acquire in November" is a question the
Event Log should be able to answer without parsing English.

## A transaction event is stamped with the time it happened

Every other Snapshot Diff event is stamped with the time of the Check
that found it, because Sleeper publishes no time for a status change.
A transaction publishes `status_updated`, so the event carries that
instead.

This is what makes the feed safe to lose. If the transactions read fails
for an hour, the next healthy Check sees the whole week's list as new,
and stamping it with its own clock would send the user news about trades
he already read about days ago. Stamped with the truth, the Alert retry
window drops anything older than four hours on its own, and the Event
Log's transaction-id keys stop anything inside the window from arriving
twice.

A transaction Otto cannot time is schema drift, not a transaction with
an unknown time. The adapter rejects the whole read and self-reports,
the same way a drifted kickoff time is rejected, because every timing
rule below is measured from that stamp: a missing one would either bury
a real trade in 1970 or dress week-old news as fresh, and both failures
are silent.

## A drop the user made himself is not news to him

Every trade sends, the user's own included: it changes his lineup, and
one confirmation of what he agreed to is worth having. A drop he made
does not. Telling him to consider a claim on the player he has just cut
would be the assistant arguing with a decision he took ten seconds ago.

## Informational Alerts sit on both ends of the confidence gate

The gate says High states the action, Medium sends and voices the doubt,
and Low is never proactive. Informational Alerts use all three rungs,
and which one they use is the whole rule:

A completed trade is a fact, so it rides at High and states itself.
ADR-0001 reserved High for certainties; a trade that has already
happened is as certain as a ruled-out starter, and hedging a fact would
read as doubt about whether the trade occurred.

A dropped Notable Player is a judgement about a claim, so it rides at
Medium and carries the case both ways: the rank that makes him worth
having, and the reasons the manager who dropped him may have been right.

Any other drop rides at Low, which the gate never sends. That is not a
special case bolted on to keep quiet - it is the gate doing its job. A
replacement-level player changing hands is an answer to a question, not
a text message.

## Notable Player cutoffs are Superflex-aware in the rank, not in the words

The cutoffs are the spec's: top-12 QB, top-24 RB, top-24 WR, top-12 TE
of the weekly projection table, priced in this league's own scoring.
Quarterbacks are ranked and carry a cutoff at all because this league
starts two of them; in a one-quarterback league a dropped QB12 is a
replacement-level body, and here he starts for most of the field.

The Superflex number the spec spells out is the replacement rank rather
than the Notable one: replacement level sits at QB 24, RB 24, WR 24 and
TE 12, because a Superflex league makes about 24 quarterbacks startable.
Both sets of ranks live in Settings, which is where the spec puts them,
so the Settings-by-chat work can move them without touching this code.

## A rank the table cannot reach is not a replacement level of zero

`PositionRanking` answers "empty" when the projection table holds fewer
players at a position than the cutoff rank. A shallow table reads as a
shallow table and the answer says so, rather than pricing replacement
level at zero and calling every bench player startable.

The same rule decides a drop this system cannot judge: no ranking means
Low confidence with the reason stated, not silence and not a guess.

## A team is judged the same way whichever team it is

`get_team_roster` builds the same week view for a league mate that the
lineup tools build for the user, and reads their lineup through the same
planner. Strengths and gaps then come from one comparison: each starting
slot against replacement level for the position filling it, and each
position's count of startable players against the slots only that
position can fill.

A trade has two sides, and valuing them by two different yardsticks
would be the fastest way to talk the user into a bad one.

## The playoff race counts; it never simulates

The spec puts Monte Carlo playoff odds out of scope, so two counts carry
the whole answer.

A team is eliminated when the number of teams already standing above the
best finish it can still reach is the size of the playoff field or more.
A team has clinched when fewer teams than the field can still reach the
standing it already holds. The first counts teams strictly above, the
second counts teams that can only draw level, so the points tiebreaker
can never overturn either: it decides only the places this math leaves
open.

The counting runs in win points - a win is two, a tie is one - rather
than in wins. Counting whole wins would call a team on 6-4-3 eliminated
behind six teams on 8-5, when winning its last game would finish it
above all six. A certainty that is only true when nobody drew is not a
certainty, and the standings already seed on the same half-win.

Games remaining come from each team's own record rather than from the
calendar: a team that has played eleven of fourteen has three left,
whichever week Sleeper says it is. Reading it off the record also
removes the gap between a week's games finishing and Sleeper advancing
its week, where the calendar would hand every team a phantom game.

Wins to clinch is the smallest number of remaining wins that makes the
second count hold. The math does not read the rest of the schedule, so
two teams that still play each other are both counted as able to win
out. That makes every "clinched" and "eliminated" conservative and true,
and the answer says so in as many words.

## Standings seed on wins, and settle on points scored

Sleeper orders its own standings that way, and the user reads the two
side by side. Ties count as half a win, which is the same order without
a second sort key. A points total arrives as a whole part plus a
two-digit fraction (`fpts` 1450 with `fpts_decimal` 50 is 1450.50);
that convention is worth confirming against the live league during the
soak.
