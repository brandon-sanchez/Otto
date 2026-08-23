# ADR-0010: Alert delivery gets an outbox, and a stale send resends

Status: accepted (2026-08-23, issue #37)

Every Alert path sends to Telegram first and records the Event Log
entry that dedups it after. CodeRabbit raised the gap on the waiver
board in #17 and was deferred to #37 so the answer would cover every
path at once. This ADR records that answer: yes, delivery gets an
outbox, and when the outbox cannot say whether a send happened, the
assistant sends again.

## The window the ordering was designed around no longer exists

The send-first ordering was a deliberate trade. `AlertIdSequence`
persists the id before the send, so a crash between Telegram accepting
a message and the Event Log recording it could never reuse an id - the
worst case was a duplicate message, and the window was two adjacent
in-process statements on a laptop that essentially never died there.

ADR-0008 changed both halves of that defense. On Lambda the process is
frozen and killed mid-invocation as a matter of course, and the Event
Log batches its write to the end of the run. The window is no longer
statement-to-statement; it is from the Telegram send to the end of the
whole Check, on the platform where dying inside it is likeliest.
ADR-0008 recorded the widening rather than solving it, and pointed
here.

## The outbox

Before the send, each Alert path writes a delivery record - the stable
Alert key, the id `AlertIdSequence` produced, and a pending status.
When Telegram accepts, the record flips to sent. Both writes go
straight through, not into the end-of-run batch: ADR-0008 already
rules that small records write through, and a delivery record that
waited for the batch would protect nothing.

The Event Log keeps batching exactly as ADR-0008 built it. Dedup moves
to the outbox: a sent record suppresses a resend even when the crash
ate the batched Event Log entry. The log remains the history; the
outbox is the delivery state.

## A stale pending record means send again

A pending record with no sent mark is ambiguous - the process died
either just before the HTTP call or just inside it. The assistant
resends, reusing the recorded id so the inline buttons stay coherent.

This preserves the value judgment the original ordering encoded. A
duplicate Alert is embarrassing; a missed lineup Alert costs points
and is silent. Assuming a stale pending record was delivered converts
the ambiguity into the silent failure. Resending converts it into a
duplicate whose window is now the milliseconds of one HTTP call
rather than the remainder of a run.

## What was rejected

- **Leaving it.** The defense was true when written and is not true
  on Lambda. An ADR defending it would document a claim the deployed
  system contradicts.
- **Record-before-send without a completion mark.** A send that fails
  is recorded as delivered, and the user never hears about a real
  problem. This is the worse ordering the codebase already refused.
- **An outbox for only the Alert classes where a duplicate hurts.**
  Two delivery behaviors, and every new Alert class reopens the
  question of which one it gets. One mechanism for every path is the
  point of settling this centrally instead of per-path in review.
