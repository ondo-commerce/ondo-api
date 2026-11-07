# ─────────────────────────────────────────────────────────────
# C · IAM — 태스크가 AWS 를 부를 때 쓰는 권한
#
# 역할이 두 종류다. 헷갈리기 쉬운데 쓰는 주체가 다르다.
#
#   실행 역할(execution)  ECS 가 태스크를 「띄울 때」 쓴다.
#                        이미지를 받고, 시크릿을 읽어 환경변수로 넣고, 로그를 만든다.
#                        앱은 이 역할을 못 쓴다
#
#   태스크 역할(task)     「앱 자신이」 AWS 를 부를 때 쓴다. S3 업로드 같은 것
#
# ⚠️ 실행 역할을 도매·소매로 나눈다. 「읽기 권한도 태스크별로 나눈다 — 소매 서버는
#    도매 DB 비밀번호를 못 읽는다」(인프라.md). 하나로 합치면 그 경계가 무너진다.
# ─────────────────────────────────────────────────────────────

# ECS 가 이 역할을 맡을 수 있게 하는 신뢰 정책. 두 역할이 같이 쓴다
data "aws_iam_policy_document" "ecs_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# ── 실행 역할 · 소매 ─────────────────────────────────────────
resource "aws_iam_role" "exec_retail" {
  name               = "${local.prefix}-exec-retail"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = { Name = "${local.prefix}-exec-retail" }
}

# ECR 에서 이미지 받기 + CloudWatch 에 로그 쓰기. AWS 가 만들어둔 정책이다
resource "aws_iam_role_policy_attachment" "exec_retail_base" {
  role       = aws_iam_role.exec_retail.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# 자기 DB 비밀번호만 읽는다. 도매 시크릿 ARN 은 여기 없다
data "aws_iam_policy_document" "read_retail_secret" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_db_instance.retail.master_user_secret[0].secret_arn]
  }
}

resource "aws_iam_role_policy" "exec_retail_secret" {
  name   = "read-retail-db-secret"
  role   = aws_iam_role.exec_retail.id
  policy = data.aws_iam_policy_document.read_retail_secret.json
}

# 소매 접점 시크릿 (MUL-87). 소매와 도매가 같은 값을 읽는다 —
# 나눠 갖는 비밀이라 양쪽 다 필요하다. DB 시크릿과 달리 서로의 것이 아니다
data "aws_iam_policy_document" "read_gateway_secret" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.gateway.arn]
  }
}

resource "aws_iam_role_policy" "exec_retail_gateway_secret" {
  name   = "read-gateway-secret"
  role   = aws_iam_role.exec_retail.id
  policy = data.aws_iam_policy_document.read_gateway_secret.json
}

# ── 실행 역할 · 도매 ─────────────────────────────────────────
resource "aws_iam_role" "exec_wholesale" {
  name               = "${local.prefix}-exec-wholesale"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = { Name = "${local.prefix}-exec-wholesale" }
}

resource "aws_iam_role_policy_attachment" "exec_wholesale_base" {
  role       = aws_iam_role.exec_wholesale.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "read_wholesale_secret" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_db_instance.wholesale.master_user_secret[0].secret_arn]
  }
}

resource "aws_iam_role_policy" "exec_wholesale_secret" {
  name   = "read-wholesale-db-secret"
  role   = aws_iam_role.exec_wholesale.id
  policy = data.aws_iam_policy_document.read_wholesale_secret.json
}

resource "aws_iam_role_policy" "exec_wholesale_gateway_secret" {
  name   = "read-gateway-secret"
  role   = aws_iam_role.exec_wholesale.id
  policy = data.aws_iam_policy_document.read_gateway_secret.json
}

# ── 태스크 역할 · 소매 ───────────────────────────────────────
#
# 앱이 직접 AWS 를 부를 때 쓴다. 실행 역할(exec_retail)과 헷갈리면 안 된다 —
# 그건 ECS 가 컨테이너를 띄우려고 쓰는 것이고(이미지 받기 · 시크릿 읽기 · 로그),
# 이건 컨테이너 안에서 도는 자바 코드가 쓴다.
#
# 오래 비어 있었다. 파일을 컨테이너 안에 쓰고 있어서 앱이 AWS 를 부를 일이
# 없었다. MUL-100 에서 S3 로 옮기며 채운다.
resource "aws_iam_role" "task_retail" {
  name               = "${local.prefix}-task-retail"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = { Name = "${local.prefix}-task-retail" }
}

# 가입 서류 버킷에 올리고 읽는다 (MUL-100).
#
# 객체 단위로만 준다. 버킷 자체에 대한 권한은 안 준다 —
#
#   ListBucket 을 안 주는 게 핵심이다. 파일 이름이 UUID 라 추측이 안 되는데,
#   목록을 받을 수 있으면 그 방어가 통째로 무너진다. 앱은 자기가 만든 키를
#   DB 에서 꺼내 쓰므로 목록이 필요 없다.
#
#   DeleteObject 도 안 준다. 증빙 서류를 앱이 지울 일이 없고, 앱이 뚫렸을 때
#   지워지는 것보다는 남아 있는 게 낫다.
#
# 상품 이미지 버킷(listing_images)은 여기 없다. 그건 도매가 올리는 것이라
# 채빈 영역이고, 지금 쓰는 코드도 없다.
data "aws_iam_policy_document" "task_retail_documents" {
  statement {
    sid    = "PutGetDocuments"
    effect = "Allow"
    actions = [
      "s3:PutObject",
      "s3:GetObject",
    ]
    resources = ["${aws_s3_bucket.documents.arn}/*"]
  }
}

resource "aws_iam_role_policy" "task_retail_documents" {
  name   = "${local.prefix}-task-retail-documents"
  role   = aws_iam_role.task_retail.id
  policy = data.aws_iam_policy_document.task_retail_documents.json
}

resource "aws_iam_role" "task_wholesale" {
  name               = "${local.prefix}-task-wholesale"
  assume_role_policy = data.aws_iam_policy_document.ecs_assume.json
  tags               = { Name = "${local.prefix}-task-wholesale" }
}

# ── Datadog API 키 (MUL-148) ─────────────────────────────────
#
# 두 실행 역할이 같이 읽는다. 사이드카 Agent 에 넣어줄 값이라 앱이 아니라
# ECS(실행 역할)가 읽는다. 게이트웨이 시크릿처럼 서로의 것이 아니라 공용이다.
#
# enable_datadog 을 켤 때만 생긴다 (ecs.tf)
data "aws_iam_policy_document" "read_datadog_secret" {
  count = var.enable_datadog ? 1 : 0

  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.datadog_api_key[0].arn]
  }
}

resource "aws_iam_role_policy" "exec_retail_datadog_secret" {
  count  = var.enable_datadog ? 1 : 0
  name   = "read-datadog-secret"
  role   = aws_iam_role.exec_retail.id
  policy = data.aws_iam_policy_document.read_datadog_secret[0].json
}

resource "aws_iam_role_policy" "exec_wholesale_datadog_secret" {
  count  = var.enable_datadog ? 1 : 0
  name   = "read-datadog-secret"
  role   = aws_iam_role.exec_wholesale.id
  policy = data.aws_iam_policy_document.read_datadog_secret[0].json
}
