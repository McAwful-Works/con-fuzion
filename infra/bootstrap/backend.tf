# This root's state lives in the bucket it creates, under its own key. The bucket can't hold the state that creates
# it, so the first apply runs with local state and then moves it in with `terraform init -migrate-state`
# (docs/runbooks/aws-setup.md, step 6). The bucket name holds the account ID, so it's passed at init time with
# -backend-config="bucket=..." rather than committed.
terraform {
  backend "s3" {
    key          = "bootstrap/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
