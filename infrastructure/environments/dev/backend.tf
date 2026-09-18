terraform {
  backend "s3" {
    bucket       = "eft-terraform-state-866317130608-us-east-1"
    key          = "banking/dev/terraform.tfstate"
    region       = "us-east-1"
    encrypt      = true
    use_lockfile = true
  }
}
