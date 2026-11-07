# apply 가 끝나면 터미널에 찍히는 값들

output "gabia_nameservers" {
  description = "가비아 「네임서버 설정」에 넣을 4개"
  value       = aws_route53_zone.root.name_servers
}

output "hosted_zone_id" {
  description = "앞으로 만들 레코드(api → ALB)가 들어갈 존"
  value       = aws_route53_zone.root.zone_id
}

output "api_certificate_arn" {
  description = "C단계에서 ALB HTTPS 리스너에 붙일 인증서 주소"
  value       = aws_acm_certificate.api.arn
}

output "api_dev_certificate_arn" {
  description = "개발 환경 ALB HTTPS 리스너에 붙일 인증서"
  value       = aws_acm_certificate.api_dev.arn
}

# ── C 단계(ECS)에서 쓸 값들 ──────────────────────────────────

output "retail_db_endpoint" {
  description = "소매 DB 주소. ECS 태스크의 DB_URL 에 들어간다"
  value       = aws_db_instance.retail.endpoint
}

output "wholesale_db_endpoint" {
  description = "도매 DB 주소"
  value       = aws_db_instance.wholesale.endpoint
}

output "retail_db_secret_arn" {
  description = "소매 DB 비밀번호. RDS 가 만들어 넣은 시크릿 — ECS 가 이걸 읽어 환경변수로 넣는다"
  value       = aws_db_instance.retail.master_user_secret[0].secret_arn
}

output "wholesale_db_secret_arn" {
  description = "도매 DB 비밀번호"
  value       = aws_db_instance.wholesale.master_user_secret[0].secret_arn
}

output "api_dev_url" {
  description = "개발 환경 API 주소"
  value       = "https://${aws_route53_record.api_dev.name}"
}

output "alb_dns_name" {
  description = "ALB 기본 주소. DNS 전파 전에 확인할 때 쓴다"
  value       = aws_lb.external.dns_name
}

# ── MUL-105 · GitHub Actions ─────────────────────────────────

output "github_deploy_role_arn" {
  description = "GitHub Actions 가 맡을 역할. 저장소 변수 AWS_DEPLOY_ROLE_ARN 에 넣는다"
  value       = aws_iam_role.github_deploy.arn
}

# DB 터널 (MUL-149). 끄면 null 이다 — load/retail/data/tunnel.sh 가 이 값을 읽는다
output "db_tunnel_instance_id" {
  description = "SSM 포트 포워딩용 EC2. enable_db_tunnel=false 면 null"
  value       = one(aws_instance.db_tunnel[*].id)
}

output "wholesale_db_host" {
  description = "도매 DB 호스트 (포트 없이). 터널의 목적지"
  value       = aws_db_instance.wholesale.address
}

output "retail_db_host" {
  description = "소매 DB 호스트 (포트 없이). 터널의 목적지"
  value       = aws_db_instance.retail.address
}
