variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "project" {
  type    = string
  default = "eft"
}

variable "vpc_cidr" {
  type    = string
  default = "10.30.0.0/16"
}

variable "oracle_ami_id" {
  type        = string
  description = "Verified Oracle-compatible AMI ID"
}

variable "oracle_instance_type" {
  type    = string
  default = "t3.xlarge"
}
