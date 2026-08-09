# ADR-0005: The Watchlist and Settings by chat

Status: accepted (2026-08-08, issue #16)

The spec pins the Watchlist triggers, the Settings document for v1 and
the twelve Ask tools. It leaves open where a Settings change lives, what
"trending up sharply" is in numbers, and how a Watchlist hit reaches the
Snapshot Diff. This ADR records what implementation decided.

## The Watchlist rides on the league's transactions

This slice first read a drop and a Snipe out of roster membership:
whoever left every roster was dropped, whoever joined one from nobody's
was added. League awareness (ADR-0004) landed a better answer first -
Sleeper's own transactions, which say what happened, name the managers,
and carry the time the move completed. Membership diffing was an
approximation of that, so it is gone.

What the Watchlist needed and transactions did not yet report is the
claim: ADR-0004 emits an event per traded player and per player dropped
to free agency, but none for a player claimed off it. That is precisely
the Snipe, so `DiffKind.ADD` joins the vocabulary and the differ emits
one per non-trade add.

Two consequences worth stating. A trade between two other managers is
not a Snipe, because the player was unreachable before it and is
unreachable after. And the user's own move is not news to him at all -
the same line ADR-0004 draws for its own drops, applied to both sides.

## Every diff event records the league status it was seen under

A Check builds a Snapshot and diffs it whatever the league is doing, so
the Event Log holds the pre-season too. Detection reads the Event Log
over a four-hour retry window rather than only this Check's events, so
the first Check after Sleeper flips the league in season would look
back into that window and Alert on everything in it.

The gate therefore covers the recording, not only the sending: the
differ stamps every event it emits with the league status, in one place
rather than per emit path, so a new event kind cannot be added without
one. Detection skips anything not seen in season. The alternative -
refusing to record outside the season - is simpler but throws the
pre-season away, and the Event Log is meant to be the season-long
record. A stamp costs one fact and keeps both properties.

An unstamped diff event is refused, not trusted. The first draft of
this rule had it the other way round, so that an Event Log written
before the stamp existed kept working. That was wrong, and wrong in the
exact shape the stamp was added to prevent: the stamp is newer than the
Event Log, so every event already recorded has no stamp, and an old log
would have Alerted on its whole recent history. Missing evidence is not
evidence of safety.

The cost of refusing is bounded and one-off: at most one retry window
of events from before the upgrade go unalerted, and a failed send is
the only thing that window really protects. The cost of trusting was
the whole defect.

The one exception is named in the code by event type rather than
implied by an absent field: a Watchlist move is only ever produced
inside the in-season branch of a Check, so it cannot have been seen at
any other time. Any future event type wanting the same exemption has to
earn it the same way.

Transactions are only read in season, so a claim or a drop cannot be
recorded outside it at all. The stamp still earns its place on the
status events, which are diffed on every Check whatever the league is
doing, and it holds the line for any kind added later.

## A Snipe always Alerts, and the user can still switch it off

"Always" means the machinery never swallows it. A Snipe rides at High
confidence, because nothing about it is an estimate, so the confidence
gate always passes it. The Lock Ladder does not silence it either: who
may hold a player is not a lineup slot, and no kickoff settles it.

An explicit Mute or a trigger switched off still silences it. Those are
the user's own standing instructions, and the spec is plain that a Mute
suppresses a notification class until it is unmuted.

## Trending up sharply is entering Sleeper's own list

A percentage rise needs a floor to stop tiny numbers shouting, and any
floor is invented. Sleeper already publishes the ranking: the most-added
players across every league over a lookback window. So "sharply" is
crossing into that list - the top 25 adds over 24 hours - having been
outside it at the last reading. It fires once per entry, it needs no
magic multiplier, and a re-entry weeks later is news again.

Otto stores the whole list it read, not the watched part of it. A player
added to the Watchlist while he is already trending has not just
entered, so he does not produce a message the user would read as new.
The very first reading of all reports nothing for the same reason:
there is no earlier list for anybody to have entered from.

## A projection update is a move of one point or more

The feed nudges numbers all day, so a move needs a size. One point in
league scoring is it - the same size the spec picked as the default
bench-over-starter edge, for the same reason: below it the number has
not told the user anything he would act on.

It is a constant, not the edge threshold the user sets. The two dials
look alike and control different things: the edge threshold decides how
loud his own lineup is, and this decides when news about a player he
only watches is news. Tying them would mean a user quieting his lineup
also stopped hearing that a watched player's week collapsed, which he
never asked for. If the constant proves wrong in use, it becomes a
Settings field of its own rather than a second meaning for an
existing one.

The first reading of a player never Alerts, because there is nothing to
compare it against, and a week with no projection for him leaves the
last reading alone rather than reading as a fall to zero.

The stored reading carries the week it belongs to, and a rollover is a
new baseline rather than a move. Every number changes when the week
turns over, and none of that is news about a player - without the week
on the reading, Tuesday would Alert once for every watched player at
once.

## Trending and projections diff against their own stored reading

Neither is part of the Snapshot: one is a national number and the other
is a week fact. They get their own last-seen document and are diffed the
same way the Snapshot is, so a move fires once and not on every Check.
The events they produce carry their own type in the Event Log, because
calling a national add count a Snapshot Diff would be a lie about where
it came from.

Each source records for itself whether it has ever been read, because
one can be down while the other answers. Comparing against a reading
that never happened would report the whole world as new the moment a
broken feed recovered.

Nothing about them reaches the wire while the Watchlist is empty. The
list is the whole reason to ask.

## Watchlist news is not a problem to verify

The recommend-and-verify loop closes problems with the user's own team.
A Watchlist Alert names something he cannot fix - somebody else's
roster move, a national add count, a projection - so verification
ignores those keys entirely. Without that, a Done tap on a Snipe would
grow into the 20-minute "I do not see a lineup change yet" note about a
lineup change that was never asked for.

## Settings get a thirteenth tool

The spec's twelve tools have no home for a Settings change or a mute
query. Folding either into `manage_watchlist` would make one tool answer
two unrelated questions, and renaming a pinned tool would be worse. So
`manage_settings` shows the Settings document and the mute list, sets
one setting, and mutes or unmutes.

Settings and mutes answer through one view because the user thinks of
them as one question - what will you tell me about? - and the spec asks
for both in compact list form. The mute list reads back in the same
words the tool accepts for an unmute, so what the user sees is what he
can type.

## The Settings document holds every trigger the spec names

Six triggers are settable, including the two whose detectors are not
built yet (the waiver Alert and league activity). A Settings screen that
hid a trigger until its detector shipped would be a worse answer than
one that lists the whole set the spec pins. The detectors read the
toggle when they arrive.

The quiet-hours field exists and stays empty. An attempt to set it says
so plainly rather than storing a value nothing honours.

## The edge threshold is a setting with a configured default

`otto.edge-threshold` is now the default a fresh install starts from,
not the number the code reads. Everything that prices a coin flip -
the bench-over-starter detector and `compare_players` - reads the stored
setting per call, so the threshold the user set by chat is the only one
in the system. Nothing is written until he changes something.

The Notable Player cutoffs work the same way, for the same reason. The
spec puts them in the Settings document and asks that the chat move
them, so `otto.notable-cutoffs` supplies the default and the dropped-
player rule reads the stored value per drop. One number answers
wherever it is read, whether league activity is judging a drop or the
chat is showing the settings back.
