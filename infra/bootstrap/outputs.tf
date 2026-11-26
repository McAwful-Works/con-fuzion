# What the owner copies into GitHub after the first apply (docs/runbooks/aws-setup.md, step 8). None of these are
# secrets: they are identifiers, and they go in GitHub *variables*.

output "aws_region" {
  description = "GitHub repo variable AWS_REGION."
  value       = local.region
}

output "state_bucket" {
  description = "GitHub repo variable TF_STATE_BUCKET, and -backend-config=\"bucket=...\" for every root."
  value       = aws_s3_bucket.state.id
}

output "plan_role_arn" {
  description = "GitHub repo variable AWS_PLAN_ROLE_ARN."
  value       = aws_iam_role.plan.arn
}

output "staging_deploy_role_arn" {
  description = "Variable AWS_DEPLOY_ROLE_ARN in the GitHub environment named staging."
  value       = aws_iam_role.deploy["staging"].arn
}

output "prod_deploy_role_arn" {
  description = "Variable AWS_DEPLOY_ROLE_ARN in the GitHub environment named production."
  value       = aws_iam_role.deploy["prod"].arn
}

output "alarm_topic_arn" {
  description = "The SNS topic for alarms. Later roots look it up by name (confuzion-alarms)."
  value       = aws_sns_topic.alarms.arn
}
