# ─────────────────────────────────────────────────────────────
# 소매 접점 시크릿 (MUL-87)
#
# 소매가 도매를 부를 때 X-Ondo-Gateway-Secret 헤더에 실어 보내고,
# 도매가 그걸 확인한다. 네트워크(내부 ALB · 보안그룹)만 믿지 않는 이유는
# VPC 안이 뚫렸을 때 도매 데이터가 통째로 열리기 때문이다.
#
# 값을 여기서 만들고 코드에는 안 적는다. 태스크가 뜰 때 ECS 가 읽어
# 환경변수로 넣어준다 — DB 비밀번호와 같은 방식이다(ecs.tf).
# ─────────────────────────────────────────────────────────────

# HTTP 헤더에 실리는 값이라 ASCII 로만 만든다. 특수문자를 빼는 것도 같은 이유다 —
# 헤더 값에 쓸 수 있는 글자가 제한적이고, 앱이 기동할 때 ASCII 검사를 한다
resource "random_password" "gateway_secret" {
  length  = 48
  special = false
}

resource "aws_secretsmanager_secret" "gateway" {
  name        = "${local.prefix}-gateway-secret"
  description = "Retail to wholesale gateway shared secret"

  # 개발 환경이라 지우면 바로 사라지게 둔다. 기본값은 30일 유예라
  # 같은 이름으로 다시 만들 때 「이미 있다」로 막힌다
  recovery_window_in_days = 0

  tags = { Name = "${local.prefix}-gateway-secret" }
}

resource "aws_secretsmanager_secret_version" "gateway" {
  secret_id     = aws_secretsmanager_secret.gateway.id
  secret_string = random_password.gateway_secret.result
}

# ─────────────────────────────────────────────────────────────
# Datadog API 키 (MUL-148)
#
# 사이드카 Agent 가 트레이스를 Datadog 으로 보낼 때 쓴다.
#
# 위 게이트웨이 시크릿과 달리 값(secret_version)을 터라폼이 안 만든다.
# 키는 Datadog 이 발급한 것이라 터라폼이 생성할 수 없고, 여기 적으면
# 코드나 state 에 키가 남는다. 껍데기만 만들고 값은 사람이 한 번 넣는다:
#
#   aws secretsmanager put-secret-value --profile ondo \
#     --secret-id ondo-dev-datadog-api-key --secret-string '<API 키>'
#
# ⚠️ 값을 넣기 전에 배포하면 태스크가 안 뜬다 — ECS 가 시크릿을 못 읽어서
#    컨테이너를 시작하지 못한다. apply 하고 값 넣고, 그다음 배포한다.
# ─────────────────────────────────────────────────────────────
resource "aws_secretsmanager_secret" "datadog_api_key" {
  # enable_datadog 을 켤 때만 만든다 (ecs.tf)
  count = var.enable_datadog ? 1 : 0

  name        = "${local.prefix}-datadog-api-key"
  description = "Datadog API key for the ECS sidecar agent"

  recovery_window_in_days = 0

  tags = { Name = "${local.prefix}-datadog-api-key" }
}
