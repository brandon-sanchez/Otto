# ADR-0001: Lineup guardrail Alert semantics

Status: accepted (2026-08-07, issue #11)

The spec pins the Alert triggers, the confidence gate, and the Lock
Ladder. It leaves a few semantic gaps that implementation had to close.
This ADR records those decisions.

## Alerts name the specific uncertainty, never a confidence level

Confidence is internal. Low is never proactive; High marks a
certainty (a ruled-out starter, a bye starter, an empty slot) and
Medium an estimate, and when candidates merge into one message the
more certain one leads. No message shows the label. A message that
carries doubt says what the doubt is: "provided he's cleared without a
snap limit", or that two projections are close enough to flip on game
day. A generic "Medium confidence" tells the user nothing he can act
on or watch for.

A bench-over-starter edge is a comparison of two estimates, so it is
always Medium and its message always carries both projected numbers.

## The model writes lineup Alerts, and code checks them

Bench-edge, lineup-legality and final-pregame Alerts are written by the
model in a recommendation-first voice, from an evidence bundle: each
player's identity, designation, lineup role and projection, the
computed problem, and the lineup moves code verified as legal. The
model chooses the move, or no change where that is legal, and writes
the message. It may state facts only from the bundle.

Before anything is sent, a check holds the answer to the bundle:
player ids and names, every number, every comparison, the move against
the legal moves, any dollar amount against a verified budget, the
evidence each claim cites, and the voice rules below. A failed answer
gets one correction attempt carrying its specific failures; if the
second answer fails too, the advice is withheld. Template advice is
never substituted silently.

Withheld does not mean silent when silence costs points. A slot that
scores zero as set, or a starter still uncertain as his game nears, is
urgent: when the model is down or its advice fails twice, the verified
facts go out as code-written text, with no recommendation the facts do
not state. A withheld edge sends nothing. Advice to make no change on
an edge is not sent either, because nobody asked.

Every run writes one Decision Record. Its run id is a hash of the Alert
key and what the evidence says, leaving out read times and the minutes
to kickoff, so a later Check over the same evidence repeats the stored
outcome without asking the model again. New evidence is a new run.

## Status changes are player decisions

A status-change Alert is a player decision written the same way. Code
builds every complete roster plan that is legal in that league: hold
him, move him to or off IR, keep him and add the best projected free
agent at his position into an open spot, drop someone else for that
free agent, or drop him. The bundle carries each plan, the plans ruled
out and why, the league's format and IR rules, his latest report, and
his age and draft position when nflverse has them. The model chooses
one plan, leads with it, and explains it; the check also holds the
plan to the legal set and requires the message to name every player
the plan drops or adds.

Legality is verified, never assumed. IR is offered only when the league
states its IR slot count, a slot is free, the league allows his
designation there, and his game has not kicked off. Taxi moves are
never offered, because Sleeper's taxi eligibility depends on facts Otto
does not read. Dropping him is offered only when a current report
explains his status. Without one, his recovery is an unknown the
advice must be conditional on, and an unknown recovery is never a
reason to cut him. The format is evidence, not a rule: there are no age,
draft-round or playoff-week cutoffs.

The status change itself is a verified fact the user asked to hear.
When the model is down or its advice fails twice, the change goes out
as code-written text, with the lineup fix when his slot scores zero,
and no advice. A starter ruled Out is still one message: when the
status transition and the legality problem merge, the transition leads
and the decision also chooses how to fill his slot.

## Messages say when the game starts

Players are introduced by full name. A message says "game starts in
roughly 25 minutes", rounded to five minutes, rather than that a player
"locks": the user acts on the clock, not on Sleeper's mechanism. The
final pregame warning must use that phrase and stays under 280
characters; other lineup Alerts stay under 600.

## One problem, one message: candidates merge per player

A starter ruled Out is one problem, but two detectors see it: the
status transition (Diff-driven) and lineup legality (state-driven).
`AlertService` groups same-Check candidates by player and sends one
message, then records every merged candidate's dedup key. Neither
detector re-fires later, and the user never gets two texts about the
same problem in the same minute.

## Unplayable starters are legality problems, never edge pairs

The edge detector excludes ruled-out and bye starters from swap
pairing. Pairing against them would price a certain zero as a small
projection edge. The legality Alert owns those slots and points at the
best playable replacement instead.

## Equal projections keep the current starter

Lineup planning, bench-edge alerts, and trade valuation share one optimizer. When two legal
players project the same points, the player already in the lineup stays
there. A zero-point swap gives the user work without an expected gain,
so it is not a Recommendation.

When neither or both players are already preferred, the
lexicographically lower stable ID wins. That final rule is total and
independent of Sleeper roster order. Trade valuation prefers every real
roster player over its synthetic replacement-level fillers on a tie,
then uses the same stable-ID fallback. Swap pairings also use that
fallback, keeping Recommendation and Event Log keys stable.

## The final warning is per player per week, derived from state

The Lock Ladder's second rung fires inside the 30 minutes before a
player's game lock when a problem is still present: an open legality or
edge candidate, or a starter still carrying an uncertain designation
(worse than Probable). One warning per player per week
(`alert:final:<season>-w<week>:<playerId>`), and an Alert about that
player already sent inside the window counts as the warning. Deriving
the rung from current state means a problem the user already fixed
never warns, with no follow-up bookkeeping.

## "Nothing after lock" differs by problem kind

A weekly lineup problem (legality, edge) is dead once the player's game
locks: the slot is burned for the week, so it never Alerts after lock.
A status transition is only suppressed while the player's game is
underway; once the game completes, the transition matters for next
week's planning and sends normally (within the Alert retry window).

## Locks and byes come from the scores endpoint

Sleeper's undocumented `/schedule/nfl/...` endpoint carries dates only.
The undocumented `/scores/nfl/regular/{season}/{week}` endpoint carries
`start_time` (epoch millis) plus home and away teams per game, verified
live 2026-08-07. Game locks and bye detection (team with no game that
week) both read from it, behind the fail-soft adapter like every other
undocumented source.
