data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

locals {
  az_a = data.aws_availability_zones.available.names[0]
  az_b = data.aws_availability_zones.available.names[1]

  tags = {
    Project     = "enterprise-funds-transfer"
    Environment = "dev"
  }
}

# ---------------- NETWORKING ----------------

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${var.project}-vpc" }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = { Name = "${var.project}-igw" }
}

resource "aws_subnet" "public_a" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.30.1.0/24"
  availability_zone       = local.az_a
  map_public_ip_on_launch = false

  tags = { Name = "${var.project}-public-a" }
}

resource "aws_subnet" "public_b" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.30.2.0/24"
  availability_zone       = local.az_b
  map_public_ip_on_launch = false

  tags = { Name = "${var.project}-public-b" }
}

resource "aws_subnet" "app" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.30.11.0/24"
  availability_zone       = local.az_a
  map_public_ip_on_launch = false

  tags = { Name = "${var.project}-app-private" }
}

resource "aws_subnet" "data" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.30.21.0/24"
  availability_zone       = local.az_a
  map_public_ip_on_launch = false

  tags = { Name = "${var.project}-data-private" }
}

# ---------------- ROUTING ----------------

resource "aws_eip" "nat" {
  domain = "vpc"

  tags = { Name = "${var.project}-nat-eip" }
}

resource "aws_nat_gateway" "main" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public_a.id

  depends_on = [aws_internet_gateway.main]

  tags = { Name = "${var.project}-nat" }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${var.project}-public-rt" }
}

resource "aws_route_table" "private" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main.id
  }

  tags = { Name = "${var.project}-private-rt" }
}

resource "aws_route_table_association" "public_a" {
  subnet_id      = aws_subnet.public_a.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "public_b" {
  subnet_id      = aws_subnet.public_b.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "app" {
  subnet_id      = aws_subnet.app.id
  route_table_id = aws_route_table.private.id
}

resource "aws_route_table_association" "data" {
  subnet_id      = aws_subnet.data.id
  route_table_id = aws_route_table.private.id
}

# ---------------- SECURITY ----------------

resource "aws_security_group" "app" {
  name_prefix = "${var.project}-app-"
  description = "Java application security group"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.project}-app-sg" }
}

resource "aws_security_group" "database" {
  name_prefix = "${var.project}-db-"
  description = "Oracle database security group"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.project}-db-sg" }
}

resource "aws_security_group" "kafka" {
  name_prefix = "${var.project}-kafka-"
  description = "Kafka security group"
  vpc_id      = aws_vpc.main.id

  tags = { Name = "${var.project}-kafka-sg" }
}

resource "aws_vpc_security_group_ingress_rule" "oracle" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = aws_security_group.app.id

  ip_protocol = "tcp"
  from_port   = 1521
  to_port     = 1521

  description = "Oracle from Java application"
}

resource "aws_vpc_security_group_ingress_rule" "kafka" {
  security_group_id            = aws_security_group.kafka.id
  referenced_security_group_id = aws_security_group.app.id

  ip_protocol = "tcp"
  from_port   = 9092
  to_port     = 9092

  description = "Kafka from Java application"
}

# Initial lab egress for package installation and AWS access.
# Restrict this after identifying service dependencies.

resource "aws_vpc_security_group_egress_rule" "app" {
  security_group_id = aws_security_group.app.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "database" {
  security_group_id = aws_security_group.database.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "kafka" {
  security_group_id = aws_security_group.kafka.id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

# ---------------- IAM / SSM ----------------

data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ec2" {
  name               = "${var.project}-ec2-ssm-role"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "ssm" {
  role       = aws_iam_role.ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${var.project}-ec2-profile"
  role = aws_iam_role.ec2.name
}

# ---------------- EC2 ----------------

resource "aws_instance" "app" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = "t3.medium"
  subnet_id              = aws_subnet.app.id
  vpc_security_group_ids = [aws_security_group.app.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  associate_public_ip_address = false

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
    encrypted   = true
  }

  tags = { Name = "bank-app-01" }

  depends_on = [aws_iam_role_policy_attachment.ssm]
}

resource "aws_instance" "kafka" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = "t3.medium"
  subnet_id              = aws_subnet.data.id
  vpc_security_group_ids = [aws_security_group.kafka.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name

  associate_public_ip_address = false

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_size = 30
    volume_type = "gp3"
    encrypted   = true
  }

  ebs_block_device {
    device_name = "/dev/sdf"
    volume_size = 50
    volume_type = "gp3"
    encrypted   = true
  }

  tags = { Name = "bank-kafka-01" }

  depends_on = [aws_iam_role_policy_attachment.ssm]
}

resource "aws_instance" "database" {
  ami                    = var.oracle_ami_id
  instance_type          = var.oracle_instance_type
  subnet_id              = aws_subnet.data.id
  vpc_security_group_ids = [aws_security_group.database.id]

  iam_instance_profile        = aws_iam_instance_profile.ec2.name
  associate_public_ip_address = false

  user_data = <<-EOF
    #!/bin/bash
    set -euxo pipefail

    dnf install -y \
      https://s3.amazonaws.com/ec2-downloads-windows/SSMAgent/latest/linux_amd64/amazon-ssm-agent.rpm

    systemctl enable amazon-ssm-agent
    systemctl start amazon-ssm-agent
  EOF

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_size = 50
    volume_type = "gp3"
    encrypted   = true
  }

  ebs_block_device {
    device_name = "/dev/sdf"
    volume_size = 100
    volume_type = "gp3"
    encrypted   = true
  }

  tags = { Name = "bank-db-01" }

  depends_on = [aws_iam_role_policy_attachment.ssm]
}
