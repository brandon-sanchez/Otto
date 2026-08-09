# ADR-0007: Local delivery of Telegram updates

Status: accepted (2026-08-08, issue #28)

The Ask loop had no front door. `TelegramWebhook.handle` was called
only by tests, and the real caller - the Lambda function URL - lands in
the last slice of the build. This ADR records how the operator's own
machine reaches the same seam, and what that costs.

## The local profile long-polls; it serves no endpoint

Telegram offers two ways in: it posts each update to a webhook, or the
bot asks for its updates with `getUpdates`. A laptop has no address
Telegram can post to, so a webhook would need an HTTP server, a tunnel
in front of it, and a `setWebhook` registration.

Long polling needs none of the three. It keeps `spring-boot-starter`
without the web starter, so the deployable stays free of an embedded
server under SnapStart; it registers nothing with Telegram, so a local
run can never rewrite the deployed assistant's front door; and it adds
no runtime dependency at all.

The cost is that `getUpdates` carries no secret-token header. The
driver therefore hands the configured secret straight to the seam. That
is honest for a local-only path: the header check guards a public URL
against the internet, and this call has no public URL. The check itself
keeps its own wire-test cover.

## One reader at a time, and the operator picks it

Telegram gives a bot one delivery path, not two: with a webhook
registered, `getUpdates` answers 409. The driver does not settle that
for itself. It calls neither `setWebhook` nor `deleteWebhook`, because
the registration it would rewrite is the deployed assistant's own front
door - and a local run that quietly took delivery of the user's
messages, or that exited leaving the webhook deleted, would be a worse
failure than a poll that does not start.

So the handoff belongs to the operator, and the README carries it:
clear the webhook by hand before a local run. The 409 is reported with
that instruction rather than swallowed. Once the deployed webhook is
live, reading messages locally at the same time needs a second bot,
which is the only way Telegram gives to have two readers.

## The driver polls on a thread of its own

`LocalCheckLoop` and `LocalNflverseJobs` are `@Scheduled` beans, and
Spring's default scheduler has one thread. A poll blocks for up to 25
seconds, which is a quarter of the Check's whole period, so a scheduled
poll would push the Check off its minute. The driver runs its own
virtual thread instead, starts on `ApplicationReadyEvent`, and stops
with the context.

## An update is confirmed before it is answered

Telegram keeps offering an update until a later poll confirms it, so
"already answered" has to be a decision the driver makes, not a
promise the wire keeps. The driver holds a watermark - the highest
update id it has taken - and stores it, so a restart cannot replay the
last unconfirmed update.

The watermark is written before the update is answered, not after. A
crash between the two then costs one reply, where the other order would
put the same question to the model again and text the user twice. A
failure while answering is caught for the same reason: one bad update
is dropped rather than offered again on every poll for ever.

## The driver gates the chat, not only the seam

ADR-0002 recorded that only the configured chat reaches the Ask loop. A
button tap does not pass that gate: it carries a callback id and an
alert id, and the seam acts on the alert. Nothing reaches the Ask loop,
but a stray tap could still record a user action.

So the driver applies the gate to every update it takes, taps included,
and reads `callback_query.message.chat` with the sender as the fallback
Telegram uses for old messages. A dropped update is still confirmed -
otherwise the poll would hand it back for ever.
