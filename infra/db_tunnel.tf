# ─────────────────────────────────────────────────────────────
# DB 터널 — 노트북에서 배포 DB 로 들어가는 임시 통로 (MUL-149)
#
# RDS 는 프라이빗이라 밖에서 못 붙는다. 부하 테스트 데이터를 넣으려면 VPC 안에서
# 대신 연결해 줄 것이 필요하다. 작은 EC2 하나를 두고 SSM Session Manager 로
# 포트 포워딩한다:
#
#   노트북 localhost:15432 ──SSM──▶ 이 EC2 ──5432──▶ 도매 RDS
#   노트북 localhost:15433 ──SSM──▶ 이 EC2 ──5432──▶ 소매 RDS
#
# 흔한 「배스천」과 다른 점 — 들어오는 포트가 하나도 없다. SSH 키도, 공인 IP 도 없다.
# 연결은 EC2 가 SSM 쪽으로 먼저 나가서 잡고, 누가 들어올 수 있는지는 IAM 이 정한다.
#
# 평소에는 없다. 쓸 때만 켠다:
#
#   terraform apply -var enable_db_tunnel=true     켜기
#   terraform apply                                끄기 (EC2 · 규칙 전부 사라짐)
#
# 터널 여는 법은 load/retail/data/tunnel.sh
# ─────────────────────────────────────────────────────────────

variable "enable_db_tunnel" {
  description = "true 면 배포 DB 에 들어가는 SSM 터널용 EC2 를 띄운다 (부하 데이터 적재용)"
  type        = bool
  default     = false
}

locals {
  db_tunnel = var.enable_db_tunnel ? 1 : 0
}

# Amazon Linux 2023 ARM. SSM 에이전트가 기본으로 들어 있다
data "aws_ssm_parameter" "al2023_arm" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

# ── 권한 ─────────────────────────────────────────────────────
# SSM 에 자기를 등록하고 세션을 받는 데 필요한 것만. AWS 관리형 정책이다
data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "db_tunnel" {
  count              = local.db_tunnel
  name               = "${local.prefix}-db-tunnel"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
  tags               = { Name = "${local.prefix}-db-tunnel" }
}

resource "aws_iam_role_policy_attachment" "db_tunnel_ssm" {
  count      = local.db_tunnel
  role       = aws_iam_role.db_tunnel[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "db_tunnel" {
  count = local.db_tunnel
  name  = "${local.prefix}-db-tunnel"
  role  = aws_iam_role.db_tunnel[0].name
}

# ── 네트워크 ─────────────────────────────────────────────────
#
# 들어오는 규칙이 없다. 나가는 건 두 가지뿐이다 —
#   443   SSM 엔드포인트 (NAT 를 거쳐 나간다)
#   5432  도매 · 소매 DB
resource "aws_security_group" "db_tunnel" {
  count       = local.db_tunnel
  name        = "${local.prefix}-db-tunnel"
  description = "SSM port-forwarding host for RDS"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.prefix}-db-tunnel" }
}

resource "aws_vpc_security_group_egress_rule" "db_tunnel_ssm" {
  count             = local.db_tunnel
  security_group_id = aws_security_group.db_tunnel[0].id
  description       = "SSM endpoints via NAT"
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "db_tunnel_to_wholesale" {
  count                        = local.db_tunnel
  security_group_id            = aws_security_group.db_tunnel[0].id
  description                  = "To wholesale RDS"
  referenced_security_group_id = aws_security_group.db_wholesale.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "db_tunnel_to_retail" {
  count                        = local.db_tunnel
  security_group_id            = aws_security_group.db_tunnel[0].id
  description                  = "To retail RDS"
  referenced_security_group_id = aws_security_group.db_retail.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

# DB 쪽 문. 터널을 끄면 이 규칙도 같이 사라져서 DB 는 다시 앱만 받는다.
#
# ⚠️ 터널은 두 DB 를 다 연다 — 「소매는 도매 DB 에 못 붙는다」는 경계(security.tf)의
#    예외다. 앱이 아니라 사람이 쓰는 통로라서, 켜 둔 동안만 생기게 했다
resource "aws_vpc_security_group_ingress_rule" "db_wholesale_from_tunnel" {
  count                        = local.db_tunnel
  security_group_id            = aws_security_group.db_wholesale.id
  description                  = "PostgreSQL from SSM tunnel host"
  referenced_security_group_id = aws_security_group.db_tunnel[0].id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

resource "aws_vpc_security_group_ingress_rule" "db_retail_from_tunnel" {
  count                        = local.db_tunnel
  security_group_id            = aws_security_group.db_retail.id
  description                  = "PostgreSQL from SSM tunnel host"
  referenced_security_group_id = aws_security_group.db_tunnel[0].id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
}

# ── 인스턴스 ─────────────────────────────────────────────────
#
# 트래픽을 옮기기만 해서 제일 작은 걸로 둔다. 시간당 0.5센트쯤
resource "aws_instance" "db_tunnel" {
  count = local.db_tunnel

  ami           = data.aws_ssm_parameter.al2023_arm.value
  instance_type = "t4g.nano"

  # 앱 서브넷이다 — NAT 로 SSM 에 나갈 수 있고, DB 서브넷과 통한다.
  # DB 서브넷에는 NAT 가 없어서 SSM 에 못 붙는다
  subnet_id                   = aws_subnet.app[0].id
  vpc_security_group_ids      = [aws_security_group.db_tunnel[0].id]
  associate_public_ip_address = false
  iam_instance_profile        = aws_iam_instance_profile.db_tunnel[0].name

  # IMDSv2 만 받는다. v1 은 SSRF 로 인스턴스 자격 증명이 새는 통로가 된다
  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
    encrypted   = true
  }

  # 새 AMI 가 나올 때마다 인스턴스를 갈아엎지 않는다. 껐다 켜면 그때 최신을 받는다
  lifecycle {
    ignore_changes = [ami]
  }

  tags = { Name = "${local.prefix}-db-tunnel" }
}
