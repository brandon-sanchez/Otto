# Deploy

How to put the assistant in the cloud and how to prove it is awake.

You need the AWS CLI signed in to the account you want, Node (for the
CDK CLI), JDK 25, and Maven. The stack goes to `us-east-1`.

## 1. Store the secrets

CloudFormation cannot create a SecureString, so the four secrets are
put by hand, once. The leaf of each parameter name is the property it
sets, so the spelling matters.

```sh
PATH_PREFIX=/otto/prod

for pair in \
  "otto.telegram.bot-token:$TELEGRAM_BOT_TOKEN" \
  "otto.telegram.chat-id:$TELEGRAM_CHAT_ID" \
  "otto.telegram.webhook-secret:$TELEGRAM_WEBHOOK_SECRET" \
  "spring.ai.openai.api-key:$OPENAI_API_KEY"
do
  aws ssm put-parameter --region us-east-1 --type SecureString --overwrite \
    --name "$PATH_PREFIX/${pair%%:*}" --value "${pair#*:}"
done
```

Pick the webhook secret yourself. It is any string of 1 to 256
characters from `A-Z a-z 0-9 _ -`; Telegram echoes it on every call and
the assistant refuses a call without it.

## 2. Build the deployable

The stack uploads `target/otto-lambda.zip`, so build it first.

```sh
mvn package     # needs JDK 25 on JAVA_HOME
```

## 3. Deploy the stack

Every CDK command runs from `infra/`, which is where `cdk.json` says how
to synthesize the stack.

```sh
cd infra
npx aws-cdk@2 bootstrap aws://ACCOUNT_ID/us-east-1   # once per account
npx aws-cdk@2 deploy -c alertEmail=you@example.com
```

The stack takes `alertEmail` and refuses to synthesize without it - an
assistant that cannot report on itself is worse than one that is not
deployed. Two optional context values: `parameterPath` (default
`/otto/prod`) and `timeZone` (default `America/Los_Angeles`, which is
when the nightly defense-versus-position build runs).

Confirm the SNS subscription in the email AWS sends, or no alarm will
reach you.

CDK warns twice that "SnapStart only supports published Lambda
versions". Expect it: the check cannot see that both functions are
reached through an alias on a published version, which they are.

## 4. Point Telegram at the webhook

The deploy prints `WebhookUrl`. Telegram delivers to a webhook or to
`getUpdates`, never to both, so registering this one stops any local
run from reading messages.

```sh
curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
  -d "url=${WEBHOOK_URL}" \
  -d "secret_token=${TELEGRAM_WEBHOOK_SECRET}" \
  -d 'allowed_updates=["message","callback_query"]'
```

To go back to running on your own machine, clear it:

```sh
curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/deleteWebhook"
```

## 5. Verify it live

Four checks, in order. The first three take a few minutes; the fourth
takes fifteen.

**The Check loop runs.** Watch the scheduled function's log for the
heartbeat line:

```sh
aws logs tail --region us-east-1 --follow \
  "$(aws cloudformation describe-stack-resources --stack-name OttoStack \
      --query "StackResources[?LogicalResourceId=='ScheduledLogs'].PhysicalResourceId" \
      --output text)"
```

One `heartbeat check-completed` a minute is the loop alive.

**The Ask loop answers.** Text the bot. A reply means the function URL,
the secret token, the chat gate, the model key and the stored
conversation all work.

**A self-report Alert arrives.** Break one feed on purpose and let one
Check run into it - point `otto.sleeper.base-url` at a host that
refuses, or revoke the model key - and the assistant should text you
that it cannot read that source. Put the setting back afterwards. This
is the Alert that tells you the assistant knows when it is blind, so it
is worth proving once rather than trusting.

**The heartbeat alarm fires.** Disable the 1-minute Check schedule and
wait fifteen minutes. Easiest in the EventBridge Scheduler console:
find the schedule whose name contains `CheckSchedule` and disable it.

By CLI it takes two steps, because `update-schedule` replaces the whole
schedule rather than patching one field, so the definition has to be
read back first:

```sh
NAME=$(aws scheduler list-schedules --region us-east-1 \
  --query "Schedules[?contains(Name,'CheckSchedule')].Name" --output text)

aws scheduler get-schedule --region us-east-1 --name "$NAME" \
  --query '{Name:Name,GroupName:GroupName,ScheduleExpression:ScheduleExpression,FlexibleTimeWindow:FlexibleTimeWindow,Target:Target,State:`DISABLED`}' \
  > /tmp/check-schedule.json

aws scheduler update-schedule --region us-east-1 \
  --cli-input-json file:///tmp/check-schedule.json
```

The alarm should go to ALARM, the email should arrive, and the
forwarder should text you. Put it back by repeating the two commands
with `` `ENABLED` `` in place of `` `DISABLED` ``. Until this one has
fired once, you do not know the assistant can tell you it has stopped.

## What it costs

Under the $20 monthly budget the stack sets, and well under it in
practice: about 44,000 Lambda invocations a month at 1024 MB for a few
seconds each, one S3 object written per minute, a few DynamoDB reads
and writes per run, and the model calls the Ask loop makes. The budget
warns at 80% of actual spend and at a forecast that would pass $20.
