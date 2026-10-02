# ─────────────────────────────────────────────────────────────
# D · ECS 서비스 — 태스크를 실제로 굴리는 것
#
# 태스크 정의가 설계도라면 서비스는 「그 설계도로 항상 2개를 띄워둬라」는 지시다.
# 하나가 죽으면 알아서 다시 띄우고, 배포하면 하나씩 갈아끼운다.
# ─────────────────────────────────────────────────────────────

locals {
  # 서비스마다 태스크 몇 개를 띄울지. 원래는 AZ 마다 하나씩 2 였다.
  # 지금은 1 로 줄였다 — 요금은 태스크 수에 그대로 비례하는데,
  # 사용자가 0명이라 두 개가 나눠 받을 트래픽이 없다.
  #
  # 배포는 여전히 안 끊긴다. 기본값이 최대 200% 라 새 태스크를 먼저 띄우고
  # 건강해진 뒤에 옛것을 내린다 — 여분은 배포하는 그 순간에만 생기면 된다.
  #
  # 대신 잃는 것: 태스크가 죽으면 ECS 가 다시 띄울 때까지(기동 3.7초 + 헬스체크)
  # 받아줄 다른 태스크가 없다. 발표나 시연 전에는 2 로 올려 둔다
  desired_count = 1

  # 앱이 뜨는 동안 헬스체크 실패를 봐주는 시간. MUL-76 에서 잰 값이
  # 기동 3.7초 · 200 까지 5초라 60초면 넉넉하다. Flyway 가 마이그레이션을
  # 돌리는 것까지 포함된 값이다
  health_grace = 60
}

resource "aws_ecs_service" "retail" {
  name            = "${local.prefix}-retail"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.retail.arn
  desired_count   = local.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets         = aws_subnet.app[*].id
    security_groups = [aws_security_group.app_retail.id]

    # 프라이빗 서브넷이라 공인 IP 를 안 준다. 밖으로는 NAT 로 나간다
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.retail.arn
    container_name   = "retail"
    container_port   = 8080
  }

  health_check_grace_period_seconds = local.health_grace

  # 배포가 실패하면 자동으로 되돌린다. 없으면 깨진 이미지를 올렸을 때
  # 태스크가 계속 죽었다 살았다 하면서 서비스가 안 뜬 채로 남는다
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # 리스너 규칙이 먼저 있어야 타깃그룹이 ALB 에 실제로 물린다
  depends_on = [aws_lb_listener_rule.retail]

  # 이미지는 이제 CI 담당이다 (MUL-105).
  #
  # 배포할 때마다 GitHub Actions 가 지시서를 새로 등록하고(8 · 9 · 10 …)
  # 서비스를 거기로 옮긴다. 터라폼은 그걸 모르니, 이 줄이 없으면 다음 apply 때
  # 「내가 만든 7번이랑 다르네」 하고 되돌려버린다 — 배포가 조용히 롤백된다.
  #
  # 터라폼은 CPU · 환경변수 · 시크릿을 계속 정한다. 그 값은 태스크 정의 리소스에
  # 그대로 남고, CI 는 최신 리비전을 복사해 이미지 한 줄만 갈아끼운다.
  #
  # ⚠️ 그래서 ecs.tf 를 고치고 apply 해도 그 자리에서는 안 뜬다. 새 리비전을
  #    만들어 두기만 한다. 다음 배포 때 반영되고, 바로 반영하려면 Actions 에서
  #    배포를 한 번 돌린다
  lifecycle {
    ignore_changes = [task_definition]
  }

  tags = { Name = "${local.prefix}-retail" }
}

resource "aws_ecs_service" "wholesale" {
  name            = "${local.prefix}-wholesale"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.wholesale.arn
  desired_count   = local.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = aws_subnet.app[*].id
    security_groups  = [aws_security_group.app_wholesale.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.wholesale.arn
    container_name   = "wholesale"
    container_port   = 8081
  }

  # 내부 ALB 에도 자기를 등록한다 (MUL-87). 타깃그룹 하나는 로드밸런서 하나에만
  # 붙으므로, 같은 태스크를 두 타깃그룹에 넣는 방식으로 두 ALB 를 받는다
  load_balancer {
    target_group_arn = aws_lb_target_group.wholesale_internal.arn
    container_name   = "wholesale"
    container_port   = 8081
  }

  health_check_grace_period_seconds = local.health_grace

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  depends_on = [aws_lb_listener_rule.wholesale, aws_lb_listener_rule.internal_retail_gateway]

  # 이미지는 이제 CI 담당이다 (MUL-105).
  #
  # 배포할 때마다 GitHub Actions 가 지시서를 새로 등록하고(8 · 9 · 10 …)
  # 서비스를 거기로 옮긴다. 터라폼은 그걸 모르니, 이 줄이 없으면 다음 apply 때
  # 「내가 만든 7번이랑 다르네」 하고 되돌려버린다 — 배포가 조용히 롤백된다.
  #
  # 터라폼은 CPU · 환경변수 · 시크릿을 계속 정한다. 그 값은 태스크 정의 리소스에
  # 그대로 남고, CI 는 최신 리비전을 복사해 이미지 한 줄만 갈아끼운다.
  #
  # ⚠️ 그래서 ecs.tf 를 고치고 apply 해도 그 자리에서는 안 뜬다. 새 리비전을
  #    만들어 두기만 한다. 다음 배포 때 반영되고, 바로 반영하려면 Actions 에서
  #    배포를 한 번 돌린다
  lifecycle {
    ignore_changes = [task_definition]
  }

  tags = { Name = "${local.prefix}-wholesale" }
}
