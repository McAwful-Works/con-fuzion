provider "aws" {
  region = "us-east-1" # D-016: one region for everything

  default_tags {
    tags = {
      Project     = "confuzion"
      Environment = "shared"
      ManagedBy   = "terraform"
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
