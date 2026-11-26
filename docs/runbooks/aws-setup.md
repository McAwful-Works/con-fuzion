# AWS setup runbook (the owner, once)

This gets a new AWS account ready for Con-Fuzion and applies the **bootstrap root** (`infra/bootstrap/`). Every later
root (network, data, compute) stores its state in the bucket this creates, and GitHub Actions reaches AWS only
through the roles it creates. D-016 has the decisions behind it.

You run every step here with your own credentials. Claude never runs `terraform apply` and never has AWS
credentials. The commands are for PowerShell on Windows. They work in a macOS or Linux shell too, except where
noted.

**What it creates** (all in `us-east-1`):
- the Terraform state bucket `confuzion-tfstate-<account-id>`;
- the GitHub OIDC identity provider, a read-only plan role, and a deploy role for staging and one for prod;

It costs almost nothing: IAM and the OIDC provider are free, and S3 storage for state is cents. The account baseline
(CloudTrail, GuardDuty, the alarm topic and a budget) comes with the second part of #103.

## 1. Lock down the root user
The root user (the email address you signed up with) can do anything, including close the account.
1. Sign in to the [AWS console](https://console.aws.amazon.com/) as root.
2. Open **IAM → Security credentials** (top-right menu → *Security credentials*).
3. Under **Multi-factor authentication (MFA)**, choose **Assign MFA device**. Use a passkey or security key, or an
   authenticator app. Store the recovery details somewhere safe that isn't the same device.
4. Under **Access keys**, make sure there are **none**. If there are, delete them. Root should never have keys.
5. Open **Account** (top-right menu), find **IAM user and role access to Billing information**, choose **Edit**, and
   tick **Activate IAM Access**. Without it, the admin login from step 2 can't see Billing, Budgets or Cost Explorer.
6. From now on, use root only for the few tasks that need it (billing contacts, closing the account).

## 2. Make an admin login with MFA
Use **IAM Identity Center** (recommended): you sign in through a web page and get short-lived credentials, so there
are no long-lived keys on your machine.
1. Still as root, open **IAM Identity Center** in `us-east-1` and choose **Enable**. When asked, enable it **with
   AWS Organizations** (an *organization instance*), which makes this account an organization of one. Don't pick
   an *account instance*: it can't assign permission sets to an AWS account, so steps 4 and 5 wouldn't work.
2. **Users → Add user**: your name and email. Accept the invitation email and set a password.
3. **Settings → Authentication → Multi-factor authentication**: require MFA *every time they sign in*, and register
   your MFA device on first sign-in.
4. **Permission sets → Create permission set → Predefined → AdministratorAccess**. Set the session duration to
   1 hour or more.
5. **AWS accounts →** select your account **→ Assign users or groups →** your user **→ AdministratorAccess**.
6. Note the **AWS access portal URL** from the Identity Center dashboard (for example `https://d-xxxxxxxxxx.awsapps.com/start`).
7. Sign out of root.

*If you'd rather use an IAM user:* **IAM → Users → Create user**, attach `AdministratorAccess`, enable console
access, then assign an MFA device to it. Create an access key only if you must (**Security credentials → Create
access key → CLI**), and never share it or put it in a file in this repo. Identity Center avoids that key entirely.

## 3. Install the tools
```powershell
winget install --id Amazon.AWSCLI -e
winget install --id Hashicorp.Terraform -e --version 1.17.2
winget install --id GitHub.cli -e
winget install --id Microsoft.PowerShell -e
```
Open a new terminal (PowerShell 7, `pwsh`, from now on), then check `aws --version` (2.x) and `terraform version`
(1.17.x). Every root pins Terraform to one minor series (`infra/bootstrap/versions.tf`), so install that series
rather than the newest release: a newer minor stops `terraform init` with *Unsupported Terraform Core version*.
Sign `gh` in with `gh auth login`. On macOS, install the CLI and `gh` with `brew install awscli gh`, and take
Terraform 1.17.x from [HashiCorp's releases](https://releases.hashicorp.com/terraform/).

## 4. Sign the CLI in
```powershell
aws configure sso
```
Answer the prompts:
- **SSO session name:** `confuzion`
- **SSO start URL:** the access portal URL from step 2.6
- **SSO region:** `us-east-1`
- **Registration scopes:** press Enter for the default.

A browser opens: sign in with MFA and allow access. Then pick your account and the `AdministratorAccess` role.
- **Default client region:** `us-east-1`
- **Output format:** `json`
- **Profile name:** `confuzion-admin`

Each time you work with AWS:
```powershell
aws sso login --profile confuzion-admin
$env:AWS_PROFILE = "confuzion-admin"
aws sts get-caller-identity
```
The last command should show your account and an `AWSReservedSSO_AdministratorAccess_…` role. (In bash:
`export AWS_PROFILE=confuzion-admin`.)

*IAM user instead:* `aws configure --profile confuzion-admin`, then paste the access key when asked.

## 5. Apply the bootstrap root
The state bucket doesn't exist yet, so the first apply keeps its state on your machine, and step 5.4 moves it into
the bucket.

1. **Set the S3 backend aside for this first run:**
   ```powershell
   cd infra/bootstrap
   mv backend.tf backend.tf.off
   terraform init
   ```
   Check whether a GitHub OIDC provider is already set up, from an earlier experiment:
   ```powershell
   aws iam list-open-id-connect-providers
   ```
   If it lists a provider ARN ending in `token.actions.githubusercontent.com`, bring it under Terraform instead of
   creating a second, which would fail at apply:
   ```powershell
   terraform import aws_iam_openid_connect_provider.github <provider-arn>
   ```
2. **Plan, and read it:**
   ```powershell
   terraform plan -out bootstrap.tfplan
   ```
   Check that:
   - it says **`Plan: 14 to add, 0 to change, 0 to destroy`** (13 if you imported the provider in step 1, and then
     a change to it is fine), and nothing is destroyed;
   - the bucket is `confuzion-tfstate-<your account ID>`;
   - each `aws_iam_role`'s `assume_role_policy` has `token.actions.githubusercontent.com:aud` =
     `sts.amazonaws.com`, and `:sub` = one of the three subjects in section 7, with `StringEquals`;
   - the plan role is `confuzion-github-plan` with `ReadOnlyAccess`, and the deploy roles are
     `confuzion-staging-github-deploy` and `confuzion-prod-github-deploy`.
3. **Apply it:**
   ```powershell
   terraform apply bootstrap.tfplan
   terraform output
   ```
   Keep the outputs. You need them in steps 5.4 and 6. If the apply stops partway with an error, fix the cause,
   then run `terraform plan -out bootstrap.tfplan` again and apply that new plan: a saved plan is stale once
   anything changed.
4. **Move the state into the bucket:**
   ```powershell
   mv backend.tf.off backend.tf
   terraform init -migrate-state -backend-config="bucket=<state_bucket output>"
   ```
   Answer `yes` when asked to copy the existing state. Then check that it worked:
   ```powershell
   terraform plan
   ```
   It must say **No changes**. Only then delete the local copies, which are now stale:
   ```powershell
   rm terraform.tfstate, terraform.tfstate.backup, bootstrap.tfplan
   ```
   (bash: `rm terraform.tfstate terraform.tfstate.backup bootstrap.tfplan`). Make sure `git status` shows
   `backend.tf` unchanged and no state files.

**Why migrate rather than keep local state:** the state then has the same versioning, encryption and locking as
every other root's, a lost or wiped laptop doesn't lose it, and CI can plan bootstrap changes in PRs like any other
root. The bucket itself has `prevent_destroy`, so Terraform refuses to delete the bucket holding its own state.

**Later changes to this root** (for example, when #105 confirms the ECS names) are a normal run:
```powershell
cd infra/bootstrap
terraform init -backend-config="bucket=<state_bucket output>"
terraform plan -out bootstrap.tfplan
terraform apply bootstrap.tfplan
```

## 6. Set the GitHub variables and environments
These values are identifiers, not secrets, so they go in GitHub **variables** (not secrets). Use the repo settings
(**Settings → Secrets and variables → Actions → Variables**), or `gh`:
```powershell
gh variable set AWS_REGION --body us-east-1
gh variable set TF_STATE_BUCKET --body <state_bucket output>
gh variable set AWS_PLAN_ROLE_ARN --body <plan_role_arn output>
```
Then create the two GitHub environments under **Settings → Environments → New environment**:

| Environment | Deployment branches and tags | Protection | Variable `AWS_DEPLOY_ROLE_ARN` |
|---|---|---|---|
| `staging` | **Selected branches and tags:** the branch `main` only | none | `staging_deploy_role_arn` |
| `production` | **Selected branches and tags:** the tag pattern `v*` only | **Required reviewers:** you | `prod_deploy_role_arn` |

The names must be exactly `staging` and `production`, because each deploy role trusts its environment by name. The
deployment rules matter: they're what stop a job on another branch from using an environment, and so its role.
```powershell
gh variable set AWS_DEPLOY_ROLE_ARN --env staging --body <staging_deploy_role_arn output>
gh variable set AWS_DEPLOY_ROLE_ARN --env production --body <prod_deploy_role_arn output>
```
On `production`, leave **Prevent self-review** off: you're its only reviewer. The repo's `v*` tag ruleset (D-009)
already limits who can create a release tag, which is the other half of what keeps prod deploys yours.

## 7. The OIDC subjects (for the CI and CD workflows, #107)
GitHub puts a `sub` claim in each job's OIDC token, and each role trusts exactly one. This repo uses GitHub's
default subject format (checked: `use_default: true`).

| Role | Trusted `sub` | The job that uses it |
|---|---|---|
| `confuzion-github-plan` | `repo:McAwful-Works/con-fuzion:pull_request` | A `pull_request` workflow job **with no `environment:`** |
| `confuzion-staging-github-deploy` | `repo:McAwful-Works/con-fuzion:environment:staging` | A `push` to `main` job with `environment: staging` |
| `confuzion-prod-github-deploy` | `repo:McAwful-Works/con-fuzion:environment:production` | A `push` of a `v*` tag job with `environment: production` |

What the workflows must do to match:
- Grant `permissions: id-token: write` (and `contents: read`) to the job, and use `aws-actions/configure-aws-credentials`
  with `role-to-assume` and `aws-region` from the variables above. No access keys anywhere.
- **The plan job must not name an environment.** If it does, its subject becomes `environment:<name>` and the plan
  role refuses it.
- **PR plans run with `-lock=false`.** The plan role can't write to the state bucket, so it can't take the lock:
  `terraform plan -lock=false -input=false`.
- **Never use `pull_request_target`** for anything that assumes a role. It runs with the base branch's context and
  is the usual way untrusted fork code gets credentials.
- **Fork PRs get no AWS access.** A `pull_request` run from a fork gets read-only permissions, with no
  `id-token: write`, so it can't get an OIDC token. Plans run only for branches in this repo, and pushing one needs
  write access.
- The deploy jobs get an `environment:` subject however they were triggered, so the environment's deployment rules
  (step 6) are what limit staging to `main` and prod to `v*` tags with your approval. Keep those rules in place.
- If the org or repo ever switches on GitHub's **immutable OIDC subjects** (`repo:Owner@id/Repo@id:…`), every role
  stops trusting its jobs until `infra/bootstrap/github.tf` changes to match.

What each role may do:
- **Plan:** read everything a plan needs (`ReadOnlyAccess`), plus the state files. It's explicitly denied reading
  objects in any other bucket, log events, secret and parameter values, and `kms:Decrypt`. So a later root must
  not manage a secret's *value* in Terraform (for example an `aws_secretsmanager_secret_version`), or the PR plan
  can't refresh it. Secret values are entered by you, outside Terraform.
- **Deploy (each environment):** register its own task-definition family, describe and update its own ECS service,
  and pass only its own task and execution roles to ECS. Both use the one image repository, `confuzion-app`:
  staging pushes each build to it, and prod deploys that same digest, so prod may only describe images. The names are in
  `infra/bootstrap/github.tf` (`TODO(#105)`: the compute root confirms them). It can't run Terraform.

## 8. Check the policies (after the first apply)
Run these in PowerShell 7 (`pwsh`, step 3), which passes the JSON argument through intact. Windows PowerShell 5.1
doesn't.

**Trust policies, with IAM Access Analyzer** (it flags overly broad or malformed trust):
```powershell
foreach ($r in "confuzion-github-plan", "confuzion-staging-github-deploy", "confuzion-prod-github-deploy") {
  $doc = aws iam get-role --role-name $r --query Role.AssumeRolePolicyDocument --output json
  aws accessanalyzer validate-policy --policy-type RESOURCE_POLICY `
    --validate-policy-resource-type AWS::IAM::AssumeRolePolicyDocument --policy-document "$doc" `
    --query "findings[].[findingType,issueCode]" --output text
}
```
No `ERROR` or `SECURITY_WARNING` lines should appear.

**Permissions, with the IAM policy simulator.** Each check should print the decision shown:
```powershell
$acct = aws sts get-caller-identity --query Account --output text
$role = "arn:aws:iam::${acct}:role"
# The prod deploy role can roll the prod service (allowed) but not staging's (implicitDeny)
aws iam simulate-principal-policy --policy-source-arn "$role/confuzion-prod-github-deploy" --action-names ecs:UpdateService `
  --resource-arns "arn:aws:ecs:us-east-1:${acct}:service/confuzion-prod/confuzion-prod-app" `
                  "arn:aws:ecs:us-east-1:${acct}:service/confuzion-staging/confuzion-staging-app" `
  --query "EvaluationResults[].[EvalResourceName,EvalDecision]" --output text
# The plan role can read the state (allowed) but no other bucket's objects (explicitDeny), and can't write (implicitDeny)
aws iam simulate-principal-policy --policy-source-arn "$role/confuzion-github-plan" --action-names s3:GetObject s3:PutObject `
  --resource-arns "arn:aws:s3:::confuzion-tfstate-${acct}/bootstrap/terraform.tfstate" `
                  "arn:aws:s3:::any-other-bucket/x" `
  --query "EvaluationResults[].[EvalActionName,EvalResourceName,EvalDecision]" --output text
```
The simulator checks the roles' permission policies, not who may assume them. The trust side is tested for real the
first time the workflows in #107 run: a job with the wrong subject fails at *Configure AWS credentials* with
`Not authorized to perform sts:AssumeRoleWithWebIdentity`.
