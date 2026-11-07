# ─────────────────────────────────────────────────────────────
# D · WAF — 외부 ALB 앞에만
#
# 내부 ALB 에는 안 붙인다. 프라이빗이라 인터넷에서 안 보인다.
#
# ⚠️ 지금 붙이는 근거는 「필요해서」가 아니라 「경험」이다. 사용자가 0명이라
#    막을 공격이 없다. 비용 상한이 넉넉해서 미리 써 보는 것이고,
#    면접에서 그렇게 말하는 게 맞다(인프라.md).
# ─────────────────────────────────────────────────────────────

# 부하 테스트 동안 무차별 대입 규칙을 「세기만」으로 바꾼다 (MUL-149).
#
# 이 규칙은 IP 하나가 5분에 2000번(초당 7번쯤)을 넘으면 막는다. k6 는 한 IP 에서
# 초당 수십~수백 번을 보내서 몇 초 만에 차단된다.
#
# true 로 두면 차단 대신 세기만 한다 — 어디서 막혔을지는 CloudWatch 지표로 남는다.
# 다른 규칙(공통 · SQL 인젝션)은 그대로 막는다.
#
#   terraform apply -var waf_rate_limit_count_only=true    테스트 시작
#   terraform apply                                        끝나면 원복
#
# ⚠️ 켜 둔 동안은 로그인 무차별 대입도 안 막힌다. 테스트가 끝나면 바로 되돌린다
variable "waf_rate_limit_count_only" {
  description = "true 면 rate-limit 규칙이 차단하지 않고 세기만 한다 (부하 테스트용)"
  type        = bool
  default     = false
}

resource "aws_wafv2_web_acl" "external" {
  name  = "${local.prefix}-waf"
  scope = "REGIONAL" # ALB 는 REGIONAL. CloudFront 면 CLOUDFRONT 다

  default_action {
    allow {}
  }

  # ── 1 · 무차별 대입 차단 ───────────────────────────────────
  #
  # 첫 목적이 이거다. 로그인에 비밀번호를 계속 갈아 넣는 걸 막는다.
  # IP 하나가 5분에 2000번을 넘으면 차단한다 — 정상 사용자는 이 근처도 안 간다.
  rule {
    name     = "rate-limit"
    priority = 1

    action {
      dynamic "block" {
        for_each = var.waf_rate_limit_count_only ? [] : [1]
        content {}
      }

      dynamic "count" {
        for_each = var.waf_rate_limit_count_only ? [1] : []
        content {}
      }
    }

    statement {
      rate_based_statement {
        limit              = 2000
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "rate-limit"
      sampled_requests_enabled   = true
    }
  }

  # ── 2 · 공통 관리형 규칙 ───────────────────────────────────
  #
  # XSS · 경로 조작 · 알려진 나쁜 입력을 AWS 가 관리해준다.
  rule {
    name     = "common"
    priority = 2

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"

        # ⚠️ 본문 크기 규칙만 끈다(차단 대신 세기만).
        #
        # 이 규칙은 요청 본문이 8KB 를 넘으면 막는데, 소매 가입에서 사업자등록증을
        # 10MB 까지 받는다(application.yml 의 max-file-size). 그대로 두면 가입이
        # 통째로 막힌다 — 켜자마자 기능 하나가 죽는 종류의 함정이다.
        #
        # 크기 제한은 이미 톰캣이 하고 있다(10MB 초과 시 413). 여기서 또 막을 이유가 없다.
        rule_action_override {
          name = "SizeRestrictions_BODY"

          action_to_use {
            count {}
          }
        }
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "common"
      sampled_requests_enabled   = true
    }
  }

  # ── 3 · SQL 인젝션 ─────────────────────────────────────────
  #
  # JPA 와 파라미터 바인딩으로 이미 막고 있지만, 앞단에서 한 겹 더 거른다.
  # 나중에 JdbcClient 로 SQL 을 직접 쓰게 되면(retailgateway) 그때 값을 한다.
  rule {
    name     = "sqli"
    priority = 3

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesSQLiRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "sqli"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.prefix}-waf"
    sampled_requests_enabled   = true
  }

  tags = { Name = "${local.prefix}-waf" }
}

resource "aws_wafv2_web_acl_association" "external" {
  resource_arn = aws_lb.external.arn
  web_acl_arn  = aws_wafv2_web_acl.external.arn
}
