# Infra — Terraform (AWS), the container image and local services

Read the root `CLAUDE.md` first. Design reference: `docs/plan.md` sections B2, B10 and B11, and `docs/security.md`.
The AWS decisions are **D-016** in `docs/decisions.md` (added by #103), which wins where the plan differs.
A change to AWS resources or IAM gets the `security` label.

## Layout
```
infra/
  docker-compose.yml   local Postgres + Valkey
  bootstrap/           root: state bucket, GitHub OIDC provider, plan and deploy roles, account baseline
  modules/<name>/      shared modules: no provider or backend blocks
  envs/staging/        root
  envs/prod/           root
```
The owner applies `bootstrap/` once, before anything else. The `Dockerfile` and `.dockerignore` are at the repo root.

## Claude plans, the owner applies
- **Never** run `terraform apply`, `destroy` or `import`, or anything that edits state (`state mv`, `state rm`,
  `state push`, `taint`, `force-unlock`).
- **Never** create, change or delete an AWS resource by any other route either (AWS CLI, SDK or console).
- Never ask for, type or store AWS credentials. Without them, say that `plan` wasn't run.
- In CI, a PR that touches `infra/` gets `fmt`, `validate` and a plan comment, never an `apply`.

## Versions
- Each root's `versions.tf` sets `required_version` to one Terraform minor series (`~> 1.N.0`) and pins each
  provider to one minor series, the same in every root. Modules state only a minimum (`>=`).
- Commit every root's `.terraform.lock.hcl`, with hashes for CI and the owner's machine:
  `terraform providers lock -platform=linux_amd64 -platform=windows_amd64`.
- A version bump is its own PR. A new provider or a third-party module needs the owner's OK; prefer plain resources.

## Naming, tags and region
- Names are `confuzion-<env>-<thing>`, where `<env>` is `staging` or `prod`. A globally unique name (S3) may end in
  the account ID, read from a data source.
- Account-wide resources, and any that both environments use (such as an image repository that prod deploys the same
  digest from), are `confuzion-<thing>` with `Environment = "shared"`. Exactly one root owns each.
- Tags are set once, in each root's provider `default_tags`: `Project = "confuzion"`, `Environment` (`staging`,
  `prod` or `shared`) and `ManagedBy = "terraform"`.
- Everything is in `us-east-1`, in one AWS account.

## State
- One state key per root, `<root path under infra/>/terraform.tfstate` (for example
  `envs/staging/terraform.tfstate`), in the bucket `bootstrap/` creates, locked with S3's own lock file
  (`use_lockfile = true`, no DynamoDB).
- `bootstrap/` creates that bucket, so its first apply uses local state. The owner then moves it into the bucket with
  `terraform init -migrate-state`.
- A backend value that contains the account ID goes to `terraform init -backend-config=…`, not into a file.
- Never commit state, saved plan files or `.terraform/`, and never paste state anywhere.

## IAM and network access
- **No static AWS keys**: none in code, `.tfvars`, GitHub secrets or env files. GitHub Actions reaches AWS only
  through OIDC, with a read-only plan role for PRs and a deploy role for `main` and `v*` tags. Trust policies pin the
  repository, the subject and the audience.
- **Least privilege.** Name the actions and the resources. No administrator managed policies. Where an API accepts
  only `*` as a resource, add a comment saying so. Limit `iam:PassRole` to the roles that are passed.
- An inbound security group rule names another security group or an AWS prefix list as its source. Nothing in a
  private subnet is reachable from the internet.
- **No hard-coded account IDs or ARNs**, in code or in PR text. Use `aws_caller_identity`, `aws_partition`,
  `aws_region`, resource attributes and module outputs.

## Secrets
- **No secret values in Terraform**: never in code, variables, `.tfvars` or outputs. Terraform creates Secrets
  Manager secrets empty, and the owner enters the values.
- **Keep them out of state too.** Have AWS manage a credential (such as RDS's managed master password), or pass it
  from an ephemeral value to a write-only argument.
- Some arguments can only be stored in state, such as a CloudFront origin's custom header and the ALB rule that
  matches it. Don't decide that alone: name the value, the argument and who can read the state, and get the owner's OK
  first.
- Claude never fetches a secret's value (`secretsmanager get-secret-value`) or prints one.

## Footprint
- Build only what the issue and D-016 name. A new AWS service needs an entry in `docs/decisions.md`.
- Stateful resources in prod have deletion protection or `prevent_destroy`. Call out in the PR any plan that destroys
  or replaces one.

## The container
- The app listens on 8080 as UID 10001. Its health endpoint is `/api/actuator/health`.
- ECS ignores a Dockerfile's `HEALTHCHECK`, so the task definition declares its own. The image has no `curl` or `wget`.
- The heap is 75% of the task's memory. Give a task at least 1 GB.
- The app writes log files under `/app/logs` as well as to stdout, so that directory (and `/tmp`) must stay writable.

## Before every PR
Run from the repo root, for each root the change affects (a module change affects every root that uses it):
```
terraform fmt -check -recursive infra
terraform -chdir=infra/<root> init            # -backend=false is enough for validate, without AWS access
terraform -chdir=infra/<root> validate
terraform -chdir=infra/<root> plan
```
Put the plan's summary and notable resources in the PR, **with account IDs, ARNs and any sensitive value removed**.

`plan` needs AWS access, which sessions don't have, and the plan workflow only arrives with #107. Until then, run
`fmt` and `validate`, say in the PR that `plan` wasn't run, and ask the owner to run it and share the summary.
