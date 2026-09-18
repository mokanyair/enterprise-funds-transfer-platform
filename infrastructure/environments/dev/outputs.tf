output "vpc_id" {
  value = aws_vpc.main.id
}

output "app_instance_id" {
  value = aws_instance.app.id
}

output "kafka_instance_id" {
  value = aws_instance.kafka.id
}

output "database_instance_id" {
  value = aws_instance.database.id
}

output "app_private_ip" {
  value = aws_instance.app.private_ip
}

output "kafka_private_ip" {
  value = aws_instance.kafka.private_ip
}

output "database_private_ip" {
  value = aws_instance.database.private_ip
}

output "nat_gateway_id" {
  value = aws_nat_gateway.main.id
}
