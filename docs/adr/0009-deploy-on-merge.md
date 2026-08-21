# ADR-0009: Merging to main is what deploys

Status: accepted (2026-08-20, issue #49)

ADR-0008 put the assistant on AWS and left the deploy to a person
typing `mvn package` and `cdk deploy`. This ADR records why that step
moved into GitHub Actions, and what the pipeline is allowed to do.

## What the manual deploy actually cost

On 2026-08-20 production was found running the build from 2026-08-13.
Two fixes had been merged and reviewed in between, and neither was
running. One of them was the preseason fix, so for a week the deployed
assistant read the wrong season's nflverse file while `main` held the
code that read the right one.

Nothing was broken in a way anyone could see. The heartbeat was
healthy, no alarm fired, and the repository looked correct. A merged
fix that is not deployed is indistinguishable from a fix that does not
work, and the assistant has no way to report the difference. That is
the failure this closes: not slow deploys, but silent ones.

## The pipeline is the build that already existed

One workflow. A pull request builds the zip, runs the app's tests and
runs the infra template assertions. A push to `main` does the same and
then deploys `OttoStack`.

The deploy installs the artifact the build produced rather than
building its own. Two `mvn package` runs of the same commit are close
enough to identical for most purposes and are not the same bytes, and
the point of testing an artifact is to ship that artifact.

Deploys are serialized. Two merges landing a minute apart would
otherwise both publish versions and both move the `live` alias, and the
alias would end up wherever the slower one finished - which is not
necessarily the newer commit.

There is no approval gate between a merge and a deploy. One user, one
league, and a review already happened on the pull request; a second
button to press is the same manual step this ADR removes, wearing a
different hat. What makes it safe to skip is that the rollback is
`cdk deploy` of an older commit, and the heartbeat and error alarms
already say when the deployed thing is wrong.

## The pipeline holds no AWS key

The repository is public. Anyone can open a pull request against it,
and a workflow that held a stored access key would be one
`pull_request_target` mistake away from handing that key out.

So there is no key. GitHub mints a short-lived OIDC token for a
workflow run, AWS trades it for session credentials, and the role's
trust policy names exactly one repository and one branch:
`repo:brandon-sanchez/Otto:ref:refs/heads/main`, matched with
StringEquals. The wildcard version of that condition is the standard
way this is got wrong - `repo:owner/Otto:*` also matches a pull request
branch pushed by a stranger.

The role carries no permissions of its own. It may assume the three CDK
bootstrap roles - lookup, file-publishing, deploy - and nothing else.
Those roles already scope what a CDK deploy may touch, so restating
that scope on the pipeline role would only create a second copy to
drift. The fourth bootstrap role, `cfn-exec`, is deliberately not
granted: CloudFormation assumes it, the CLI does not, and it is the one
that can create anything in the account.

## The role lives in a stack the pipeline cannot deploy

`DeployRoleStack` is separate from `OttoStack`, and the workflow
deploys `OttoStack` by name rather than `--all`. So the pipeline never
holds the template that defines its own permissions, and widening the
deploy role takes a human at a terminal rather than a commit landing on
main.

The cost is that a bare `cdk deploy` no longer works - CDK refuses when
an app has more than one stack and asks which. That refusal is the
feature.

## What the pipeline deliberately does not contain

- **A staging deploy** - ADR-0008 ruled out a dev stage, and a pipeline
  is not a reason to reopen it.
- **A manual approval step** - see above; it is the manual deploy again.
- **A release tag or a version number** - the commit on `main` is the
  version, and CloudFormation already keeps the previous template.
- **A rollback job** - rolling back is deploying an older commit, which
  is the same path as any other deploy and stays tested for that reason.

`DeployRoleStackTest` asserts the trust condition, the three assumable
roles and the absence of a wildcard, because each of those is a way the
pipeline could quietly become someone else's.
