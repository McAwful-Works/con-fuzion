# GitHub Actions reaches AWS only through these roles, with short-lived OIDC credentials and no stored keys.
# Each role trusts exactly one `sub` claim from this repo, and the audience is pinned. Both are matched with
# StringEquals: no wildcards. docs/runbooks/aws-setup.md ("The OIDC subjects") explains which job gets which.

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # No thumbprint_list: IAM checks GitHub's certificate against its own trusted root CAs.
}

locals {
  # The keys are the name prefixes (confuzion-<env>-...). The values are the GitHub environments the deploy jobs
  # run in. A job in an environment gets `sub = repo:<repo>:environment:<name>`, whatever branch or tag started it,
  # so the environment's deployment rules in GitHub (main only, v* tags only) pin the ref.
  deploy_environments = {
    staging = "staging"
    prod    = "production"
  }

  github_subjects = merge(
    { plan = "repo:${local.github_repo}:pull_request" },
    { for env, gh_env in local.deploy_environments : env => "repo:${local.github_repo}:environment:${gh_env}" },
  )
}

data "aws_iam_policy_document" "github_trust" {
  for_each = local.github_subjects

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [each.value]
    }
  }
}

# --- The plan role: PRs run `terraform plan -lock=false` with it. It can read, but it can't change anything. ---

resource "aws_iam_role" "plan" {
  name               = "confuzion-github-plan"
  description        = "Read-only terraform plan for pull requests in ${local.github_repo}"
  assume_role_policy = data.aws_iam_policy_document.github_trust["plan"].json
}

resource "aws_iam_role_policy_attachment" "plan_read_only" {
  role       = aws_iam_role.plan.name
  policy_arn = "arn:${local.partition}:iam::aws:policy/ReadOnlyAccess"
}

data "aws_iam_policy_document" "plan" {
  statement {
    sid       = "ReadState"
    actions   = ["s3:ListBucket"]
    resources = [aws_s3_bucket.state.arn]
  }
  statement {
    sid       = "ReadStateObjects"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.state.arn}/*"]
  }

  # ReadOnlyAccess can also read data that a plan never needs: objects in other buckets (CloudTrail and ALB logs,
  # which hold invite tokens), log events, and anything a KMS decrypt would reveal. A PR's code runs with this role,
  # so those reads are denied outright.
  statement {
    sid           = "DenyObjectReadsOutsideState"
    effect        = "Deny"
    actions       = ["s3:GetObject", "s3:GetObjectVersion"]
    not_resources = ["${aws_s3_bucket.state.arn}/*"]
  }
  statement {
    sid    = "DenyDataReads"
    effect = "Deny"
    actions = [
      "kms:Decrypt",
      "secretsmanager:GetSecretValue",
      "ssm:GetParameter",
      "ssm:GetParameters",
      "ssm:GetParametersByPath",
      "ssm:GetParameterHistory",
      "logs:GetLogEvents",
      "logs:FilterLogEvents",
      "logs:StartQuery",
      "logs:StartLiveTail",
      "logs:GetLogRecord",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "plan" {
  name   = "read-state-deny-data"
  role   = aws_iam_role.plan.id
  policy = data.aws_iam_policy_document.plan.json
}

# --- The deploy roles: app deploys only (push the image, roll the ECS service), never Terraform. ---

locals {
  # TODO(#105): the compute root creates these resources. Confirm each name there and update it here if it differs.
  deploy_targets = {
    for env in keys(local.deploy_environments) : env => {
      ecr_repository = "confuzion-${env}-app"
      cluster        = "confuzion-${env}"
      service        = "confuzion-${env}-app"
      task_family    = "confuzion-${env}-app"
      task_role      = "confuzion-${env}-task"
      execution_role = "confuzion-${env}-task-execution"
    }
  }
}

data "aws_iam_policy_document" "deploy" {
  for_each = local.deploy_targets

  statement {
    sid       = "EcrLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"] # GetAuthorizationToken has no resource-level permissions
  }
  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = ["arn:${local.partition}:ecr:${local.region}:${local.account_id}:repository/${each.value.ecr_repository}"]
  }
  statement {
    sid       = "RegisterTaskDefinition"
    actions   = ["ecs:RegisterTaskDefinition"]
    resources = ["arn:${local.partition}:ecs:${local.region}:${local.account_id}:task-definition/${each.value.task_family}:*"]
  }
  statement {
    sid       = "DescribeTaskDefinition"
    actions   = ["ecs:DescribeTaskDefinition"]
    resources = ["*"] # DescribeTaskDefinition has no resource-level permissions
  }
  statement {
    sid       = "DescribeCluster"
    actions   = ["ecs:DescribeClusters"]
    resources = ["arn:${local.partition}:ecs:${local.region}:${local.account_id}:cluster/${each.value.cluster}"]
  }
  statement {
    sid       = "RollService"
    actions   = ["ecs:UpdateService", "ecs:DescribeServices"]
    resources = ["arn:${local.partition}:ecs:${local.region}:${local.account_id}:service/${each.value.cluster}/${each.value.service}"]
  }
  statement {
    sid     = "PassTaskRoles"
    actions = ["iam:PassRole"]
    resources = [
      "arn:${local.partition}:iam::${local.account_id}:role/${each.value.task_role}",
      "arn:${local.partition}:iam::${local.account_id}:role/${each.value.execution_role}",
    ]
    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "deploy" {
  for_each = local.deploy_targets

  name               = "confuzion-${each.key}-github-deploy"
  description        = "App deploys to ${each.key} from the ${local.deploy_environments[each.key]} GitHub environment"
  assume_role_policy = data.aws_iam_policy_document.github_trust[each.key].json
}

resource "aws_iam_role_policy" "deploy" {
  for_each = local.deploy_targets

  name   = "deploy-${each.key}"
  role   = aws_iam_role.deploy[each.key].id
  policy = data.aws_iam_policy_document.deploy[each.key].json
}
