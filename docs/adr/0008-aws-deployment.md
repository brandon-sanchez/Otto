# ADR-0008: The assistant runs itself on AWS

Status: accepted (2026-08-09, issue #19)

Every slice before this one ran on the operator's laptop. The Check
loop was a `@Scheduled` method, the Telegram front door was a long
poll, and storage was a directory of JSON files. This ADR records what
carries each of the three in the cloud, and what the stack deliberately
does not contain.

AWS lands last and stays thin, as the spec's build order says. Nothing
here changes what the assistant decides; it changes only where the
deciding happens.

## Two entry points, one deployable

The whole app deploys as one zip and is invoked through two handlers: a
scheduled function that runs the Check and the two nflverse jobs, and a
webhook function that Telegram posts to. One artifact means one build,
one set of dependencies, and one place a behavior can live.

Both run on arm64, Java 25, 1024 MB, with SnapStart on published
versions.

The Spring context is built from each handler's **constructor**, and
the reason is worth stating because the obvious alternative is wrong.
Building it in a static initializer on `OttoRuntime` looks equivalent
and is not: Java initializes a class on first active use, and nothing
uses that class until a handler asks it for a bean, which is the first
invocation. The snapshot would then hold an application that had never
started, and every execution environment would pay a full Spring start
while the user waited. Lambda constructs the handler during init, so
the constructor is the hook that actually runs before the snapshot is
taken.

The alarm forwarder deliberately does not do this. It has no SnapStart,
and an init phase without SnapStart is limited to 10 seconds, which is
less than Spring takes.

SnapStart restores a *version*, never `$LATEST`. Both functions
therefore sit behind an alias, and the schedules and the function URL
target the alias. A schedule pointed at the bare function would run a
cold Spring start every minute.

The webhook is a Lambda function URL. Telegram needs one public POST
endpoint and nothing else - no routes, no authorizers, no usage plans -
so API Gateway would add a hop, a bill, and a second thing to
configure. The URL's auth type is NONE because Telegram signs nothing
AWS can check; the guard is the secret token Telegram echoes, which
`TelegramWebhook` already checks in constant time.

## Three schedules, and no schedule for anything derived

EventBridge Scheduler holds exactly what the spec names: the 1-minute
Check, the hourly nflverse timestamp check, and the nightly
defense-versus-position build. Each names its job in the payload, and
`ScheduledJob` refuses a name this build does not have rather than
falling back to a Check - a schedule naming a job that is gone is a
deployment mistake, and running the wrong job would hide it.

The Lock Ladder, the Tuesday waiver Alert and the 20-minute Done
follow-up get no schedule. They ride the 1-minute loop and the Event
Log, which is what keeps their timing and the Check's from drifting
apart.

The scheduled function is limited to one run at a time. Two overlapping
runs would read the same stored documents and each write what the other
had not seen. The Check does not retry - the next tick is one minute
away and is the retry. The nflverse jobs do retry, because their next
tick is an hour or a day away.

## Storage splits by how a document grows

The spec asks for one S3 bucket for big documents and one DynamoDB
table for small records, with a minute of writes batched into one
object. `DocumentPlacement` is the rule: small means a document whose size is
settled by its shape and stays well inside DynamoDB's 400 KB item limit
however long the season runs - Settings, the Watchlist, Mutes, the
conversation and three markers. Big means everything else: the
Snapshots, the Player Directory, the nflverse feeds,
defense-versus-position and the Event Log.

Small is the named list and big is the default, because of what each
mistake costs. A document that should have been small and goes to S3
costs a slot in the bundle. A document that should have been big and
goes to DynamoDB works until the season pushes it past 400 KB, which
happens in the middle of a season rather than at a deploy. So the
document nobody has thought about lands in the store that cannot be
outgrown.

The big documents live in one object. A Check touches the Snapshot, the
Event Log and the feeds, and batching them costs one round trip a
minute rather than one per document. The run reads the object once and
writes it once, at the end, whether or not the run finished - a Check
that half succeeded still keeps what it learned.

Two entry points write that object: the Check and the webhook both
append to the Event Log. The write therefore carries the ETag the run
read, S3 refuses a write whose ETag has moved, and the backend re-reads
and reapplies this run's documents on top of the other writer's. The
cost of the bundle is that a minute which changed one Snapshot rewrites
every big document with it; the benefit is one request instead of nine,
and a stored set that is always internally consistent.

Reapplying is only sound where the other writer left this run's
documents alone. Where both changed the same one - the Event Log is the
only realistic case - this run's value was derived from a version that
no longer exists, and writing it would drop the user's tap. There is no
general way to merge two documents at this level, so the backend
compares each document against what it held when the run first touched
it and refuses loudly when that has moved.

The two entry points answer that refusal differently, because what
they lose is not the same. The Check errors: the error alarm sees it,
and the next minute's run reads the Event Log with the tap already in
it. Losing one Check costs a minute of latency on news that is at most
a minute old anyway.

The webhook cannot shrug in the same way. The tap is the user's, he
has already been told it worked - the acknowledgement goes out while
the update is handled, before the run stores anything - and no later
run will retry it for him. So a refused webhook write answers Telegram
with a failure, and Telegram sends the update again. That is safe
because the Event Log keys on the event id: the same tap applied twice
appends once. Any other failure still answers 200 and is let go, since
a body this build cannot handle would fail the same way on every
retry.

The batching costs one guarantee the file storage gave. An Alert is
sent to Telegram during the run, and the Event Log entry that dedups it
is stored at the end, so a crash between the two re-sends that Alert
next minute. That window is the price of the spec's "batched into one
object", and it is the same class of problem as issue #37, which is
open on whether Alert delivery needs an outbox. It is recorded here
rather than solved here.

The small records write straight through. They are small and few, so
batching would buy nothing and would cost the guarantee that an Alert
id is stored before the Alert goes out. Reads are strongly consistent,
because the webhook writes what the next Check reads a moment later.

`JsonStore` stays the seam every caller holds. It now owns only the
JSON, and hands bytes to a `DocumentBackend` - the local file tree or
the AWS pair. No caller changed.

## Secrets are read once, at init

The bot token, the chat id, the webhook secret and the model key are
SSM Parameter Store SecureStrings under one path. `SsmSecrets` is an
`EnvironmentPostProcessor`: it runs before the context is built, so the
values are already in the environment the beans read, and under
SnapStart they are inside the restored snapshot. A parameter's leaf
name is the property it sets, so adding a secret needs no code.

The cost is the point of the design, and it is real: rotating a secret
needs a new function version, not just a new parameter value. That is
the right trade for four secrets that change about never, against
paying for a Parameter Store read on every Check.

Parameter Store rather than Secrets Manager: SecureStrings are free,
hold as much, and rotate by hand either way for a personal bot.

The lookup is triggered by the parameter path, not by the profile.
Profiles settle later than an `EnvironmentPostProcessor` runs, and a
laptop that quietly reached for Parameter Store would be found out only
when a message failed to send.

## The assistant reports on itself

CloudWatch logs keep 14 days. Two alarms feed one SNS topic, and the
topic reaches the user twice: email, which keeps, and a forwarder
Lambda that posts to the Telegram chat, which the user reads in time.

The forwarder is its own function on purpose. What it reports on is the
assistant being broken, and a forwarder living inside the assistant
would be broken with it.

The error alarm counts `ERROR` lines in both application log groups.
The heartbeat alarm counts one line every Check logs, and treats
missing data as breaching - a Check loop that stopped is the failure
the user would otherwise never see, because nothing errors and the
assistant simply goes quiet. The marker is logged whether or not the
pre-draft cadence let the Check do any work: a Check that ran is a loop
that is alive.

Both alarms are log metric filters rather than a metric the app puts.
That keeps `PutMetricData` off every function's policy and keeps the
app free of any code that exists only to be watched.

The bucket keeps versions and the table keeps point-in-time recovery.
Neither was asked for, and both are kept deliberately: one object holds
the whole season's record, so a single bad write could take all of it,
and a version is what that is recovered from. Because the object is
rewritten every minute, a lifecycle rule expires noncurrent versions
after seven days - long enough to notice and roll back, short of the
44,000 versions a month the bucket would otherwise keep.

A monthly Budget watches the spend against $20 and warns at 80% of
actual and at a forecast that would pass 100%. The assistant serves one
user, so a bill that climbs is a bug, not growth.

It is worth being exact about what a Budget does, because the name
invites the wrong reading: it notifies, and it does not stop spending.
Nothing in AWS caps a Lambda, an S3 request or a DynamoDB write by
money. The things that actually bound the spend here are the reserved
concurrency on the scheduled function, the 14-day log retention, and
the bucket's lifecycle rule. The Budget is how the operator finds out
that one of those three has failed.

## What the stack deliberately does not contain

Each of these was considered and left out:

- **API Gateway** - a function URL is the whole front door one bot needs.
- **Secrets Manager** - Parameter Store SecureStrings cost nothing and hold as much.
- **RDS, and a VPC** - there is no database, and nothing private to reach.
- **ECS, Fargate, App Runner** - nothing runs between Checks.
- **SQS, Step Functions** - the Event Log already sequences the work.
- **Terraform, SAM** - CDK in the app's own language, so one build and one review cover both.
- **A dev stage** - one user, one league, one place for it to run.

`OttoStackTest` asserts the absence of each, so removing one by
accident is a failing test rather than a surprise bill.

## The IaC is one CDK stack in Java

One stack, in the language the app is written in, tested by synthesizing
the template and reading the ticket's acceptance criteria back off it.
The stack lives in `infra/` with its own build, because the app's
deployable must not carry the CDK libraries.

IAM is least-privilege per function. The bucket grant names the one
object key the app writes, so a function that went wrong could not fill
the bucket with anything else. The forwarder reads secrets and nothing
else.
