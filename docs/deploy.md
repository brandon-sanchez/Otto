# Deploy

GitHub Actions deploys the assistant to `us-east-1` on every push to
`main`. This page is the setup that happens once, and the manual path
for when you need it.

The CDK app holds two stacks, so every command names the one it means.
`OttoStack` is the assistant, and Actions deploys it. `OttoDeployRoleStack`
is the role Actions deploys as, and only a human deploys that.

You need the AWS CLI signed in, Node, JDK 25 and Maven.

## 1. Store the secrets

CloudFormation cannot create encrypted parameters, so these are set once
by hand. The name after the path is the property it sets, so the
spelling matters.

```sh
for pair in \
  "otto.telegram.bot-token:$TELEGRAM_BOT_TOKEN" \
  "otto.telegram.chat-id:$TELEGRAM_CHAT_ID" \
  "otto.telegram.webhook-secret:$TELEGRAM_WEBHOOK_SECRET" \
  "spring.ai.openai.api-key:$OPENAI_API_KEY"
do
  aws ssm put-parameter --region us-east-1 --type SecureString --overwrite \
    --name "/otto/prod/${pair%%:*}" --value "${pair#*:}"
done
```

Choose the webhook secret yourself - any string of `A-Z a-z 0-9 _ -`.
Telegram echoes it on every call, and the assistant refuses a call
without it.

## 2. Deploy

```sh
mvn package                                          # builds the Lambda zip
cd infra
npx aws-cdk@2 bootstrap aws://ACCOUNT_ID/us-east-1   # once per account
npx aws-cdk@2 deploy OttoStack -c alertEmail=you@example.com
```

`alertEmail` is required and the stack will not synthesize without it.
Optional: `parameterPath` (default `/otto/prod`) and `timeZone` (default
`America/Los_Angeles`, when the nightly build runs).

Confirm the subscription email AWS sends, or no alarm will reach you.

**On a new AWS account**, add `-c scheduledReservedConcurrency=0`. New
accounts are capped at 10 concurrent Lambda executions, and AWS refuses
a reservation that would leave fewer than 10 unreserved - so none can be
made. Request a quota increase for Lambda concurrent executions, then
redeploy without the flag to limit the Check to one run at a time.

## 3. Hand the deploys to GitHub Actions

Once, so the workflow can reach AWS without a stored key. `alertEmail`
is needed here too, because the app synthesizes both stacks.

```sh
npx aws-cdk@2 deploy OttoDeployRoleStack -c alertEmail=you@example.com
```

It prints `DeployRoleArn`. Give the workflow that and the same address:

```sh
gh secret set AWS_DEPLOY_ROLE_ARN --body "$DEPLOY_ROLE_ARN"
gh secret set ALERT_EMAIL --body 'you@example.com'
```

The role trusts one repository and one branch, spelled two ways.
GitHub sends a subject carrying the numeric ids of the owner and the
repository, so a rename does not break the trust; the readable
spelling is listed beside it in case GitHub goes back to sending that
one. Moving the repo to another account means new ids, and so means
editing `SUBJECTS` in `DeployRoleStack` and deploying it again by
hand.

The ids come from GitHub, not from guesswork:

```sh
gh api repos/OWNER/REPO/actions/oidc/customization/sub
```

## 4. Point Telegram at the webhook

The deploy prints `WebhookUrl`.

```sh
curl -s "https://api.telegram.org/bot${TELEGRAM_BOT_TOKEN}/setWebhook" \
  -d "url=${WEBHOOK_URL}" \
  -d "secret_token=${TELEGRAM_WEBHOOK_SECRET}" \
  -d 'allowed_updates=["message","callback_query"]'
```

Telegram delivers to a webhook or to `getUpdates`, never both, so this
stops a local run from reading messages. To go back to running locally,
call `deleteWebhook`.

## 5. Check it works

- **The Check loop.** Tail the scheduled function's log group.
  `heartbeat check-completed` should appear once a minute.
- **The Ask loop.** Text the bot. A reply exercises the function URL, the
  secret token, the chat gate and the model.
- **The alarm.** Disable `CheckSchedule` in the EventBridge Scheduler
  console and wait fifteen minutes. An email and a Telegram message
  should arrive. Re-enable it afterwards. Until it has fired once, you do
  not know the assistant can tell you it has stopped.
