# The bootstrap root: the account-wide pieces every other root depends on. The owner applies it once, by hand,
# with their own credentials (docs/runbooks/aws-setup.md). CI only ever plans it.

terraform {
  # One minor series (infra/CLAUDE.md). Native S3 state locking (use_lockfile) needs 1.10 or later, so no DynamoDB
  # lock table is needed.
  required_version = "~> 1.17.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.74.0"
    }
  }
}
