# ─────────────────────────────────────────────────────────────
# B · RDS — 도매·소매 두 대
#
# 물리적으로 다른 DB 다. 한 트랜잭션·조인으로 두 DB 를 묶을 수 없고,
# 서버 간 연동은 API 호출로 한다. 인스턴스를 나눈 게 그 경계의 실체다.
# ─────────────────────────────────────────────────────────────

locals {
  # 로컬 compose 가 postgres:16 이라 맞춘다. 16.x 최신이고 db.t4g.micro 에서
  # Multi-AZ 가 된다. 마이너 버전은 AWS 가 알아서 올린다(auto_minor_version_upgrade)
  postgres_version = "16.15"
}

# ── 서브넷 그룹 — RDS 를 어디에 둘지 ──────────────────────────
#
# AWS 가 반드시 요구한다. Multi-AZ 로 두려면 AZ 가 다른 서브넷이 둘 이상이어야
# 한다 — 예비본을 다른 AZ 에 두는 게 Multi-AZ 의 전부다.
# 도매·소매가 같은 그룹을 쓴다. 어느 서브넷에 놓을지가 같아서다.
# 격리는 서브넷이 아니라 보안그룹이 한다.
resource "aws_db_subnet_group" "main" {
  name        = "${local.prefix}-db"
  description = "Private DB subnets - no route to internet"
  subnet_ids  = aws_subnet.db[*].id

  tags = { Name = "${local.prefix}-db-subnet-group" }
}

# ── 소매 DB ──────────────────────────────────────────────────
resource "aws_db_instance" "retail" {
  identifier = "${local.prefix}-retail"

  engine         = "postgres"
  engine_version = local.postgres_version
  instance_class = "db.t4g.micro" # 사용자 0명. 느리면 이 줄만 고쳐 apply 하면 교체된다

  # 스토리지는 늘릴 수는 있어도 줄일 수는 없다. 작게 시작한다
  allocated_storage = 20
  storage_type      = "gp3"

  # 개인정보가 들어간다(사업자등록증·연락처). 켜는 건 만들 때만 되고
  # 나중에 켜려면 스냅샷으로 새 인스턴스를 만들어 옮겨야 한다
  storage_encrypted = true

  db_name  = "ondo_retail" # 로컬과 같은 이름이라 접속 문자열이 같은 모양이다
  username = "ondo"

  # 비밀번호를 우리가 안 만든다. RDS 가 만들어 Secrets Manager 에 넣고 교체까지 한다.
  # 테라폼 코드에도 state 에도 값이 안 남는다 — state 가 지금 로컬 파일이라 특히 중요하다
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db_retail.id]

  # 켜면 다른 AZ 에 예비본이 생겨 AZ 하나가 죽어도 산다. 대신 요금이 두 배다.
  # 지금은 껐다 — 사용자가 0명이라 AZ 장애로 잃을 게 없는데 둘 값은 그대로 나갔다.
  # 예비본은 평소에 못 읽으므로 끈다고 읽기가 느려지지도 않는다.
  # 발표나 시연 전에 이 줄만 true 로 되돌리면 된다
  multi_az = false

  # 인터넷에서 직접 못 붙는다. 서브넷에 나가는 길이 없어서 어차피 안 되지만 명시한다
  publicly_accessible = false

  # 1 일치는 남긴다. Multi-AZ 를 끄면 0 도 되지만, 0 은 시점 복구가 아예 없어진다 —
  # 실수로 지운 걸 되돌릴 방법이 사라지는 값이라 아끼는 금액에 비해 손해가 크다.
  # 백업은 DB 크기(20GB)까지 공짜라 실제로 더 나가는 돈도 없다
  backup_retention_period    = 1
  auto_minor_version_upgrade = true

  # 위 변경을 다음 유지보수 창까지 기다리지 않고 바로 적용한다.
  # Multi-AZ 를 끄는 건 예비본을 떼는 것뿐이라 끊김이 없다.
  # ⚠️ 인스턴스 클래스처럼 교체가 필요한 변경도 즉시 적용되므로 운영에서는 뺀다
  apply_immediately = true

  # ⚠️ 개발 환경이라 지우기 쉽게 둔다. 12월에 계정이 닫히면 정리해야 한다.
  # 운영 환경을 만들 때는 둘 다 반대로 간다
  deletion_protection = false
  skip_final_snapshot = true

  tags = { Name = "${local.prefix}-retail-db" }
}

# ── 도매 DB ──────────────────────────────────────────────────
resource "aws_db_instance" "wholesale" {
  identifier = "${local.prefix}-wholesale"

  engine         = "postgres"
  engine_version = local.postgres_version
  instance_class = "db.t4g.micro"

  allocated_storage = 20
  storage_type      = "gp3"
  storage_encrypted = true

  db_name  = "ondo_wholesale"
  username = "ondo"

  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.db_wholesale.id]

  # 소매와 같은 이유로 껐다. 되돌릴 때는 두 줄을 같이 되돌린다
  multi_az            = false
  publicly_accessible = false

  backup_retention_period    = 1
  auto_minor_version_upgrade = true

  apply_immediately = true

  deletion_protection = false
  skip_final_snapshot = true

  tags = { Name = "${local.prefix}-wholesale-db" }
}
