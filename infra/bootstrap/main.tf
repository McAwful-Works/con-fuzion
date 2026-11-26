# The bootstrap root: the account-wide pieces every other root depends on. The owner applies it once, by hand,
# with their own credentials (docs/runbooks/aws-setup.md). CI only ever plans it.

terraform {
  # 1.10 is the first release with native S3 state locking (use_lockfile), so no DynamoDB lock table is needed.
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.74"
    }
  }
}

provider "aws" {
  region = "us-east-1" # D-016: one region for everything

  default_tags {
    tags = {
      Project     = "con-fuzion"
      Environment = "shared"
      ManagedBy   = "terraform"
      Root        = "infra/bootstrap"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  account_id  = data.aws_caller_identity.current.account_id
  partition   = data.aws_partition.current.partition
  region      = data.aws_region.current.region
  github_repo = "McAwful-Works/con-fuzion"
}
