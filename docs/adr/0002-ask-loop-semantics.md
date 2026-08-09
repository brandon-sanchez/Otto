# ADR-0002: Ask loop semantics

Status: accepted (2026-08-08, issue #13)

The spec pins the two LLM lanes, the twelve tools and the rolling
conversation window. It leaves a few semantic gaps that the Lane A
walking skeleton had to close. This ADR records those decisions.

## Asks read the stored Snapshot; they never build a new one

The Check owns Sleeper's polling cadence, and the Freshness Ceiling
means a fresh poll would not be fresher anyway. So an Ask answers from
the current Snapshot. Before the first Check the tool says it has no
Snapshot instead of building one, which keeps one writer for Snapshot
state.

Week facts (the week, the projections priced in league scoring, the
starting slots, the week's games) are the exception: both lanes build
them through `WeekFactsBuilder`, so a Check and an Ask read one week
the same way, with the same fail-soft self-reports.

The consequence, recorded plainly: an Ask still reads the league
document and those week facts through the adapter on every question.
Those reads are conditional GETs against the same ETag cache the Check
fills, so they cost a 304 rather than a payload, but they are not free.
The spec's per-tool rule - "a tool fetches live only when its data is
older than its cadence interval" - is not implemented here. It wants a
per-source fetch clock that the Check must not share, because the
Check's whole job is to poll every minute. That belongs with the
remaining tools, not with the first four.

## Locked players keep their slots in the optimal lineup

Sleeper locks a player at their game's kickoff, bench included. A
lineup recommendation that moved a locked player would recommend
something the user cannot do. So `recommend_lineup` pins locked
starters to their slots and optimizes only what is still movable. Both
totals count the locked players, so the current and optimal numbers
compare like for like.

## A lineup total is what the lineup scores as set

A starter who cannot play (ruled out, on bye) or who has no projection
counts zero in the total, never "no projection available". The slot
line still carries the reason, so the model can name the zero. Without
this rule an illegal lineup would flatter itself by leaving its dead
slots out of the sum.

## A what-if without a player to sit uses the weakest slot the fit allows

"What if I start Goedert" names one player, not a swap. The answer
prices the swap the user would actually make: the weakest starter whose
slot accepts the incoming player makes the room. Naming a sit-side
player explicitly always wins over this default.

## "Legal" in a what-if means Sleeper would take it

`whatif_lineup` reports `legal: false` only when the slot rejects the
position or one of the two players has locked. Starting a player on
bye or ruled Out is a legal lineup and a certain zero; that belongs in
the notes, not in a legality flag, so the two failures stay tellable
apart.

## Only the configured chat reaches the Ask loop

The webhook secret guards the endpoint, and the assistant serves one
user by design. A text message from any other chat is dropped before
the model call, so a stray or replayed update can never spend tokens.

## Depth is a reply-length control, not a mode

"why", "more" and their kin swap the closing instruction of the system
prompt from the 2-to-5-line brief to a full-reasoning one. A depth word
only counts inside a short message, so "I want more points from my
flex" stays a fresh question. There is no stored mode: the next
ordinary question is short again, which is what "short by default" has
to mean.

## An outage reply is not an answer

When the model is unreachable the user still gets an honest line, but
the exchange is not recorded in the conversation window. A spell of
outages would otherwise evict the real conversation and come back to
the model later as its own prior words.
