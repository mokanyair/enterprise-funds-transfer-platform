provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = "enterprise-funds-transfer"
      Environment = "dev"
      ManagedBy   = "terraform"
    }
  }
}
