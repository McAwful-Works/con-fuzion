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
- a CloudTrail trail (every region) into `confuzion-cloudtrail-<account-id>`;
- GuardDuty;
- the SNS topic `confuzion-alarms`;
- a monthly cost budget that alerts that topic.

It costs very little on its own. The first trail of management events is free, and so are IAM, the OIDC provider and
SNS email at this volume. S3 storage is cents. GuardDuty has a 30-day free trial and is then priced by the volume
of events it analyses, which is small for a quiet account: its console shows an estimate during the trial.

## 1. Lock down the root user
The root user (the email address you signed up with) can do anything, including close the account.
1. Sign in to the [AWS console](https://console.aws.amazon.com/) as root.
2. Open **IAM → Security credentials** (top-right menu → *Security credentials*).
3. Under **Multi-factor authentication (MFA)**, choose **Assign MFA device**. Use a passkey or security key, or an
   authenticator app. Store the recovery details somewhere safe that isn't the same device.
4. Under **Access keys**, make sure there are **none**. If there are, delete them. Root should never have keys.
5. From now on, use root only for the few tasks that need it (billing contacts, closing the account).

## 2. Make an admin login with MFA
Use **IAM Identity Center** (recommended): you sign in through a web page and get short-lived credentials, so there
are no long-lived keys on your machine.
1. Still as root, open **IAM Identity Center** and choose **Enable** (in `us-east-1`).
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
winget install --id Hashicorp.Terraform -e
```
Open a new terminal, then check: `aws --version` (2.x) and `terraform version` (1.10 or later). On macOS:
`brew install awscli hashicorp/tap/terraform`.

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

## 5. Choose the budget
Create `infra/bootstrap/terraform.tfvars` (git ignores `*.tfvars`) with your monthly limit in US dollars:
```hcl
monthly_budget_usd = 50
```
You get an email when actual spend passes 80% of it, or the forecast for the month passes 100%. Once M6 is built,
most of the bill is the always-on pieces: NAT gateways (three), load balancers, Valkey nodes and prod's Aurora
minimum. Price them in the [AWS Pricing Calculator](https://calculator.aws/) and set the budget to what you're
willing to spend. You can change it any time and re-apply.

## 6. Apply the bootstrap root
The state bucket doesn't exist yet, so the first apply keeps its state on your machine, and step 6.4 moves it into
the bucket.

1. **Set the S3 backend aside for this first run:**
   ```powershell
   cd infra/bootstrap
   mv backend.tf backend.tf.off
   terraform init
   ```
2. **Plan, and read it:**
   ```powershell
   terraform plan -out bootstrap.tfplan
   ```
   Check that:
   - it says **`Plan: 26 to add, 0 to change, 0 to destroy`**: nothing is changed or destroyed;
   - the two buckets are `confuzion-tfstate-<your account ID>` and `confuzion-cloudtrail-<your account ID>`;
   - each `aws_iam_role`'s `assume_role_policy` has `token.actions.githubusercontent.com:aud` =
     `sts.amazonaws.com`, and `:sub` = one of the three subjects in section 9, with `StringEquals`;
   - the plan role is `confuzion-github-plan` with `ReadOnlyAccess`, and the deploy roles are
     `confuzion-staging-github-deploy` and `confuzion-prod-github-deploy`;
   - the budget's `limit_amount` is your number.

   If it fails with **"detector already exists"** (GuardDuty was turned on before) or **"Provider with url …
   already exists"** (a GitHub OIDC provider was added before), import the existing one rather than deleting it:
   ```powershell
   terraform import aws_guardduty_detector.main <detector-id>   # aws guardduty list-detectors
   terraform import aws_iam_openid_connect_provider.github <provider-arn>   # aws iam list-open-id-connect-providers
   ```
   and plan again.
3. **Apply it:**
   ```powershell
   terraform apply bootstrap.tfplan
   terraform output
   ```
   Keep the outputs. You need them in steps 6.4, 7 and 8.
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

## 7. Subscribe your email to the alarm topic
Terraform doesn't hold your email address. Subscribe it yourself:
```powershell
aws sns subscribe --topic-arn <alarm_topic_arn output> --protocol email --notification-endpoint <your email>
```
Or in the console: **SNS → Topics → confuzion-alarms → Create subscription → Email**. AWS sends a confirmation
email: click **Confirm subscription**. Until you do, alarms and budget alerts go nowhere. To check:
```powershell
aws sns list-subscriptions-by-topic --topic-arn <alarm_topic_arn output>
```
The `SubscriptionArn` should be a full ARN, not `PendingConfirmation`.

## 8. Set the GitHub variables and environments
These values are identifiers, not secrets, so they go in GitHub **variables** (not secrets). Use the repo settings
(**Settings → Secrets and variables → Actions → Variables**), or `gh`:
```powershell
gh variable set AWS_REGION --body us-east-1
gh variable set TF_STATE_BUCKET --body <state_bucket output>
gh variable set AWS_PLAN_ROLE_ARN --body <plan_role_arn output>
gh variable set MONTHLY_BUDGET_USD --body <your number from step 5>
```
(A PR plan of this root needs the budget too: the workflow passes it as `TF_VAR_monthly_budget_usd`.)
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
(`gh` creates an environment that doesn't exist yet, but set its deployment rules and reviewers in the web UI.)

## 9. The OIDC subjects (for the CI and CD workflows, #107)
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
  is the usual way untrusted fork code gets credentials. (Its subject wouldn't match the plan role anyway.)
- **Fork PRs get no AWS access.** A `pull_request` run from a fork gets read-only permissions, with no
  `id-token: write`, so it can't get an OIDC token. Plans run only for branches in this repo, and pushing one needs
  write access.
- The deploy jobs get an `environment:` subject however they were triggered, so the environment's deployment rules
  (step 8) are what limit staging to `main` and prod to `v*` tags with your approval. Keep those rules in place.
- If the org or repo ever switches on GitHub's **immutable OIDC subjects** (`repo:Owner@id/Repo@id:…`), every role
  stops trusting its jobs until `infra/bootstrap/github.tf` changes to match.

What each role may do:
- **Plan:** read everything a plan needs (`ReadOnlyAccess`), plus the state files. It's explicitly denied reading
  objects in any other bucket, log events, secret and parameter values, and `kms:Decrypt`. So a later root must
  not manage a secret's *value* in Terraform (for example an `aws_secretsmanager_secret_version`), or the PR plan
  can't refresh it. Secret values are entered by you, outside Terraform.
- **Deploy (each environment):** push to its own ECR repository, register its own task-definition family, describe
  and update its own ECS service, and pass only its own task and execution roles to ECS. The names are in
  `infra/bootstrap/github.tf` (`TODO(#105)`: the compute root confirms them). It can't run Terraform.

## 10. Check the policies (after the first apply)
These need PowerShell 7 (`pwsh`), which passes the JSON argument through intact. Windows PowerShell 5.1 doesn't.

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
# The plan role can read the state (allowed) but not CloudTrail's logs (explicitDeny), and can't write (implicitDeny)
aws iam simulate-principal-policy --policy-source-arn "$role/confuzion-github-plan" --action-names s3:GetObject s3:PutObject `
  --resource-arns "arn:aws:s3:::confuzion-tfstate-${acct}/bootstrap/terraform.tfstate" `
                  "arn:aws:s3:::confuzion-cloudtrail-${acct}/AWSLogs/x" `
  --query "EvaluationResults[].[EvalActionName,EvalResourceName,EvalDecision]" --output text
```
The simulator checks the roles' permission policies, not who may assume them. The trust side is tested for real the
first time the workflows in #107 run: a job with the wrong subject fails at *Configure AWS credentials* with
`Not authorized to perform sts:AssumeRoleWithWebIdentity`.
