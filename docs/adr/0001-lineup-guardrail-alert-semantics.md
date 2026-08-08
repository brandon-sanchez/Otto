# ADR-0001: Lineup guardrail Alert semantics

Status: accepted (2026-08-07, issue #11)

The spec pins the Alert triggers, the confidence gate, and the Lock
Ladder. It leaves a few semantic gaps that implementation had to close.
This ADR records those decisions.

## Point-edge Alerts always ride at Medium confidence

A bench-over-starter edge is a comparison of two estimates. The gate
says High states the action and Medium voices the doubt; an estimate
difference is doubt by nature, whatever its size. So an edge Alert is
always Medium and always carries both projected numbers. High stays
reserved for certainties: a ruled-out starter, a bye starter, an empty
slot.

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
