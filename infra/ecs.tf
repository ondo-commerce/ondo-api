# ─────────────────────────────────────────────────────────────
# C · ECS — 앱이 도는 곳
#
# Fargate 라 관리할 EC2 인스턴스가 없다. OS 패치 · SSH · 디스크를 안 본다.
#
# 여기까지가 「띄울 준비」다. 실제로 태스크를 굴리는 서비스는 D 에서 만든다 —
# 서비스는 ALB 타깃그룹에 자기를 등록해야 해서 ALB 가 먼저 있어야 한다.
# ─────────────────────────────────────────────────────────────

# 브라우저에서 API 를 부를 화면 주소. 서버마다 다르다 —
# 소매 API 는 소매 화면만, 도매 API 는 도매 POS 만 받는다.
# 여러 개면 쉼표로 잇는다.
# 어느 환경으로 뜰지 (MUL-104).
#
# 스프링이 이 값으로 읽을 설정 파일을 고른다 —
#   dev   application-deploy.yml + application-dev.yml   시드 · 쿠키 개방
#   prod  application-deploy.yml + application-prod.yml  둘 다 없음
#
# 전에는 이미지(Dockerfile)에 prod 가 박혀 있었다. 배포 프로필이 하나뿐이라
# 그래도 됐지만, 지금 이 환경은 실제로 개발 환경이다. 이름을 사실대로 붙인다.
#
# 운영을 띄울 때는 이 값을 prod 로 준다. 그것만으로 시드와 쿠키 개방이 같이 꺼진다.
#
# 아래 태스크 정의가 "deploy," 를 앞에 붙여 넘긴다 (MUL-103). 순서가 곧
# 우선순위다 — 뒤에 켜진 프로필이 이긴다.
variable "spring_profile" {
  description = "ECS 태스크가 쓸 스프링 프로필 (dev | prod)"
  type        = string
  default     = "dev"

  validation {
    condition     = contains(["dev", "prod"], var.spring_profile)
    error_message = "dev 나 prod 만 된다. local 은 로컬 전용이라 배포에 쓰면 컨테이너 안 localhost 를 본다."
  }
}

variable "retail_cors_origins" {
  description = "소매 API 를 부를 화면"
  type        = string

  # localhost:3001 은 ⚠️ 임시 개방 (MUL-110). 운영 띄우기 전에 뺀다 — MUL-103.
  # 창은이가 로컬 화면으로 이 배포 API 를 부르며 개발한다. 쿠키도 같이 풀었다
  # (application-dev.yml 의 same-site: none). 둘 중 하나만 있으면 로그인이 안 된다.
  default = "https://ddmondo.co.kr,http://localhost:3001"
}

variable "wholesale_cors_origins" {
  description = "도매 API 를 부를 화면"
  type        = string
  default     = "https://pos.ddmondo.co.kr"
}

resource "aws_ecs_cluster" "main" {
  name = local.prefix

  # 태스크가 어디서 왜 멈췄는지 콘솔에서 볼 수 있게 한다. 공짜다
  setting {
    name  = "containerInsights"
    value = "disabled" # ⚠️ 켜면 CloudWatch 지표 요금이 붙는다. 필요해지면 켠다
  }

  tags = { Name = local.prefix }
}

# ── 로그 ─────────────────────────────────────────────────────
#
# 컨테이너 표준출력이 여기로 간다. Fargate 라 서버에 들어가서 로그 파일을
# 볼 수가 없다 — 로그는 전부 이리로 나온다.
locals {
  log_retention_days = 7 # 개발 환경. 오래 두면 요금이 붙는다
}

resource "aws_cloudwatch_log_group" "retail" {
  name              = "/ecs/${local.prefix}-retail"
  retention_in_days = local.log_retention_days
  tags              = { Name = "${local.prefix}-retail-logs" }
}

resource "aws_cloudwatch_log_group" "wholesale" {
  name              = "/ecs/${local.prefix}-wholesale"
  retention_in_days = local.log_retention_days
  tags              = { Name = "${local.prefix}-wholesale-logs" }
}

# ── 태스크 정의 ──────────────────────────────────────────────
#
# 「이 이미지를 이만한 자원으로, 이 환경변수와 함께 띄워라」는 설계도다.
# 실제로 뜨는 건 D 의 서비스가 이걸 보고 만든다.
#
# 처음엔 512 CPU / 1024 MB 였다. Fargate 는 조합이 정해져 있어서 256 CPU 를 쓰면
# 메모리를 512·1024·2048 중에서만 고를 수 있다. 스프링 부트에 512MB 는 빠듯하다.
#
# Datadog 을 켜면 1024 / 2048 로 올라간다 (MUL-148). 태스크 안에 Agent 사이드카가
# 같이 돌아 자원을 나눠 쓴다. ⚠️ Fargate 요금이 그대로 두 배가 된다 — MUL-144 에서
# 줄인 것과 반대 방향이라, 끄면 원래 크기로 돌아가게 묶었다.

locals {
  task_cpu    = var.enable_datadog ? 1024 : 512
  task_memory = var.enable_datadog ? 2048 : 1024

  # ECS 가 시크릿을 읽어 환경변수로 넣어준다. 뒤의 `:password::` 는
  # RDS 가 만든 JSON({"username":..,"password":..})에서 password 키만 꺼내라는 뜻이다.
  # 앱은 그냥 환경변수로 받는다 — application-prod.yml 이 이미 그렇게 돼 있다
  retail_secret    = "${aws_db_instance.retail.master_user_secret[0].secret_arn}:password::"
  wholesale_secret = "${aws_db_instance.wholesale.master_user_secret[0].secret_arn}:password::"

  # 소매 접점 시크릿 (MUL-87). 문자열 하나를 통째로 넣은 것이라
  # 위와 달리 뒤에 `:키::` 를 안 붙인다
  gateway_secret = aws_secretsmanager_secret.gateway.arn

  # 소매가 도매를 부르는 주소. HTTP 다 — VPC 안에서만 도는 트래픽이라
  # 인증서를 붙이지 않았다(alb_internal.tf)
  wholesale_base_url = "http://${aws_lb.internal.dns_name}"
}

# ── Datadog APM (MUL-148) ────────────────────────────────────
#
# 태스크마다 Agent 를 사이드카로 하나씩 띄운다. Fargate 는 호스트가 없어서
# 「호스트마다 Agent 하나」를 못 하고, 같은 태스크 안 컨테이너끼리는
# localhost 로 통한다(awsvpc). 앱의 dd-java-agent 가 localhost:8126 으로
# 트레이스를 보내면 사이드카가 받아 Datadog 으로 넘긴다.
#
# 로그는 여전히 CloudWatch 다. 여기서는 트레이스만 본다.

# 켜고 끄는 스위치. 기본은 꺼짐이다.
#
# 꺼져 있으면 Datadog 이 하나도 안 생긴다 — 사이드카 · tracer(JAVA_TOOL_OPTIONS) ·
# API 키 시크릿 · 읽기 권한 전부. Task 크기도 512 / 1024 로 돌아간다.
# dd-java-agent jar 는 이미지에 남지만 켜는 줄이 없어서 안 돈다.
#
# 켜는 순서 — 키가 비어 있으면 태스크가 안 뜬다:
#   1. terraform apply -var enable_datadog=true
#   2. 키 넣기 (secrets.tf 의 put-secret-value)
#   3. 배포
#
# ⚠️ 켰다가 끄면 키 시크릿도 지워진다(유예 0일). 다시 켤 때 키를 다시 넣는다
variable "enable_datadog" {
  description = "true 면 Datadog Agent 사이드카와 APM tracer 를 붙인다"
  type        = bool
  default     = false
}

# 계정이 AP1(도쿄)에 있다. 인프라가 서울이라 가장 가까운 곳으로 골랐다.
# ⚠️ 사이트는 계정에 묶여 있어 나중에 못 바꾼다. 다른 값을 주면 Agent 가
#    키를 「모르는 키」로 받아 트레이스가 조용히 안 들어간다
variable "datadog_site" {
  description = "Datadog 사이트 (계정이 있는 리전). 예: datadoghq.com · ap1.datadoghq.com"
  type        = string
  default     = "ap1.datadoghq.com"
}

locals {
  # 메이저 버전만 고정한다. 마이너는 Agent 가 하위 호환을 지킨다.
  # ARM64 이미지가 같은 태그에 들어 있다
  datadog_agent_image = "public.ecr.aws/datadog/agent:7"

  # Agent 는 tracer 에 env 를 다시 붙이지 않는다. 앱과 같은 값을 따로 준다
  datadog_env = var.spring_profile

  # 두 태스크가 같이 쓰는 사이드카 정의. 서비스별로 다른 건 로그 그룹뿐이다
  datadog_agent_container = {
    name  = "datadog-agent"
    image = local.datadog_agent_image

    # essential = false — Agent 가 죽어도 앱은 계속 응답한다. 트레이스가 잠깐
    # 비는 것이 API 가 내려가는 것보다 낫다. true 면 Agent 하나 때문에 태스크가 갈린다
    essential = false

    portMappings = [{
      containerPort = 8126 # APM 트레이스 수신
      protocol      = "tcp"
    }]

    environment = [
      # Fargate 라는 걸 알려야 호스트 대신 태스크 메타데이터로 자기를 식별한다
      { name = "ECS_FARGATE", value = "true" },
      { name = "DD_SITE", value = var.datadog_site },
      { name = "DD_APM_ENABLED", value = "true" },
      { name = "DD_ENV", value = local.datadog_env },
    ]

    secrets = [
      { name = "DD_API_KEY", valueFrom = one(aws_secretsmanager_secret.datadog_api_key[*].arn) },
    ]

    healthCheck = {
      command     = ["CMD-SHELL", "agent health"]
      interval    = 30
      timeout     = 5
      retries     = 3
      startPeriod = 15
    }
  }

  # 앱 컨테이너에 붙는 tracer 설정. 꺼져 있으면 빈 목록이다.
  #
  # -javaagent 를 Dockerfile 이 아니라 여기서 켠다. jar 는 이미지에 들어 있지만
  # 로컬 docker run 에는 Agent 가 없어서, 켜 두면 연결 실패 로그만 쌓인다.
  #
  # DD_VERSION 은 여기 없다 — 배포 커밋 해시라 CI 가 이미지와 같이 넣는다
  # (.github/workflows/*-api.yml 의 render 단계). Datadog 이 꺼져 있으면 아무도 안 읽는다
  datadog_app_env = {
    for svc in ["retail", "wholesale"] : svc => var.enable_datadog ? [
      { name = "JAVA_TOOL_OPTIONS", value = "-javaagent:/app/dd-java-agent.jar" },
      { name = "DD_SERVICE", value = "ondo-${svc}" },
      { name = "DD_ENV", value = local.datadog_env },
    ] : []
  }
}

resource "aws_ecs_task_definition" "retail" {
  family                   = "${local.prefix}-retail"
  requires_compatibilities = ["FARGATE"]

  # 팀 노트북이 셋 다 M 시리즈다. ARM 으로 맞추면 에뮬레이션 없이 빌드하고
  # Fargate 요금도 20% 싸다. 로컬에서 돌린 것과 같은 아키텍처라
  # 「로컬에선 됐는데」가 안 생긴다.
  #
  # ⚠️ GitHub Actions 로 넘어가면 러너 아키텍처를 여기에 맞춰야 한다.
  #    x86 러너에서 빌드한 이미지는 이 태스크에서 안 뜬다.
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  network_mode = "awsvpc" # Fargate 는 이것만 된다. 태스크마다 자기 IP 를 받는다
  cpu          = local.task_cpu
  memory       = local.task_memory

  execution_role_arn = aws_iam_role.exec_retail.arn
  task_role_arn      = aws_iam_role.task_retail.arn

  container_definitions = jsonencode(concat([{
    name = "retail"

    # ⚠️ 이 :latest 는 이제 자리표시자다 (MUL-105).
    #
    # 배포할 때마다 GitHub Actions 가 이 리비전을 복사해서 이미지만 커밋 해시로
    # 바꿔 새 리비전을 등록한다. 실제로 도는 태스크는 그쪽이라 여기 적힌 값은
    # 안 쓰인다 — 서비스가 task_definition 변경을 무시한다(service.tf).
    #
    # 그래도 이 줄을 지우면 안 된다. 태스크 정의에 image 는 필수고,
    # CI 는 「이미 있는 리비전」을 복사하는 방식이라 원본이 있어야 한다
    image = "${aws_ecr_repository.retail.repository_url}:latest"

    essential = true
    portMappings = [{
      containerPort = 8080
      protocol      = "tcp"
    }]

    environment = concat([
      # deploy 가 앞, 환경이 뒤다. 스프링은 뒤에 켜진 프로필이 이기므로
      # 이 순서라야 dev/prod 가 deploy 의 값을 덮을 수 있다 (MUL-103).
      #
      # 전에는 application.yml 의 profiles.group 이 deploy 를 붙였는데, 그러면
      # deploy 가 뒤로 가서 반대로 동작했다.
      { name = "SPRING_PROFILES_ACTIVE", value = "deploy,${var.spring_profile}" },
      { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.retail.endpoint}/ondo_retail" },
      { name = "DB_USERNAME", value = "ondo" },
      { name = "CORS_ALLOWED_ORIGINS", value = var.retail_cors_origins },

      # 개발 환경 시드(MUL-110)는 여기 없다. application-dev.yml 로 옮겼다 (MUL-104).
      #
      # 환경변수로 주면 운영 태스크에도 붙일 수 있어서, 시드 계정이 운영에 안 들어가는 걸
      # 사람이 기억해서 막아야 한다. 프로필 파일에 두면 dev 일 때만 읽히니 실수로 못 켠다.
      # 도매를 부르는 주소 (MUL-87). 내부 ALB 다
      { name = "WHOLESALE_BASE_URL", value = local.wholesale_base_url },

      # 가입 서류를 둘 버킷 (MUL-100). 전에는 컨테이너 안에 썼는데 Fargate 라
      # 태스크가 갈릴 때마다 사라졌다. 앱이 이 값을 못 받으면 기동에 실패한다 —
      # 조용히 서류만 안 올라가는 것보다 낫다
      { name = "DOCUMENTS_BUCKET", value = aws_s3_bucket.documents.bucket },

      # SDK 가 리전을 환경에서 알아내기도 하지만 ECS 가 늘 넣어주는 값이 아니다.
      # 없으면 기동이 아니라 첫 업로드에서 터진다
      { name = "AWS_REGION", value = "ap-northeast-2" },

      # Datadog 을 켜면 tracer 설정이 뒤에 붙는다 (MUL-148, local.datadog_app_env)
    ], local.datadog_app_env.retail)

    secrets = [
      { name = "DB_PASSWORD", valueFrom = local.retail_secret },
      # 도매를 부를 때 헤더에 실을 값 (MUL-87)
      { name = "ONDO_GATEWAY_SECRET", valueFrom = local.gateway_secret },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.retail.name
        "awslogs-region"        = "ap-northeast-2"
        "awslogs-stream-prefix" = "ecs"
      }
    }
    },

    ],
    # Datadog Agent 사이드카 (MUL-148). 켤 때만 들어간다.
    # 로그는 앱과 같은 그룹, 접두사만 다르다
    [for c in [local.datadog_agent_container] : merge(c, {
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.retail.name
          "awslogs-region"        = "ap-northeast-2"
          "awslogs-stream-prefix" = "datadog"
        }
      }
    }) if var.enable_datadog],
  ))

  tags = { Name = "${local.prefix}-retail" }
}

resource "aws_ecs_task_definition" "wholesale" {
  family                   = "${local.prefix}-wholesale"
  requires_compatibilities = ["FARGATE"]

  # 팀 노트북이 셋 다 M 시리즈다. ARM 으로 맞추면 에뮬레이션 없이 빌드하고
  # Fargate 요금도 20% 싸다. 로컬에서 돌린 것과 같은 아키텍처라
  # 「로컬에선 됐는데」가 안 생긴다.
  #
  # ⚠️ GitHub Actions 로 넘어가면 러너 아키텍처를 여기에 맞춰야 한다.
  #    x86 러너에서 빌드한 이미지는 이 태스크에서 안 뜬다.
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  network_mode = "awsvpc"
  cpu          = local.task_cpu
  memory       = local.task_memory

  execution_role_arn = aws_iam_role.exec_wholesale.arn
  task_role_arn      = aws_iam_role.task_wholesale.arn

  container_definitions = jsonencode(concat([{
    name = "wholesale"

    # 소매와 같다 — :latest 는 자리표시자고 실제 태그는 CI 가 정한다 (MUL-105)
    image     = "${aws_ecr_repository.wholesale.repository_url}:latest"
    essential = true
    portMappings = [{
      containerPort = 8081
      protocol      = "tcp"
    }]

    environment = concat([
      # deploy 가 앞, 환경이 뒤다. 스프링은 뒤에 켜진 프로필이 이기므로
      # 이 순서라야 dev/prod 가 deploy 의 값을 덮을 수 있다 (MUL-103).
      #
      # 전에는 application.yml 의 profiles.group 이 deploy 를 붙였는데, 그러면
      # deploy 가 뒤로 가서 반대로 동작했다.
      { name = "SPRING_PROFILES_ACTIVE", value = "deploy,${var.spring_profile}" },
      { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.wholesale.endpoint}/ondo_wholesale" },
      { name = "DB_USERNAME", value = "ondo" },
      # 이 값은 실제로 적용된다.
      #
      # 한동안 무시됐었다 — CorsConfig 는 ondo.cors.allowed-origins 를 읽는데
      # application-deploy.yml 에 그걸 환경변수로 잇는 줄이 없었다. 이름이
      # 자동으로 안 맞기 때문이다(그러려면 ONDO_CORS_ALLOWEDORIGINS 여야 한다).
      # MUL-103 에서 명시적으로 이었다 — application-deploy.yml 의
      # `allowed-origins: ${CORS_ALLOWED_ORIGINS:}`.
      { name = "CORS_ALLOWED_ORIGINS", value = var.wholesale_cors_origins },

      # 개발 환경 시드(MUL-110)는 여기 없다. application-dev.yml 로 옮겼다 (MUL-104).
      #
      # 환경변수로 주면 운영 태스크에도 붙일 수 있어서, 시드 계정이 운영에 안 들어가는 걸
      # 사람이 기억해서 막아야 한다. 프로필 파일에 두면 dev 일 때만 읽히니 실수로 못 켠다.

      # Datadog 을 켜면 tracer 설정이 뒤에 붙는다 (MUL-148, local.datadog_app_env)
    ], local.datadog_app_env.wholesale)

    secrets = [
      { name = "DB_PASSWORD", valueFrom = local.wholesale_secret },
      # 소매가 보낸 헤더를 대조할 값 (MUL-87). 없으면 앱이 안 뜬다 —
      # 시크릿을 빠뜨린 채 소매 접점이 열려 있는 것보다 낫다
      { name = "ONDO_GATEWAY_SECRET", valueFrom = local.gateway_secret },
    ]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        "awslogs-group"         = aws_cloudwatch_log_group.wholesale.name
        "awslogs-region"        = "ap-northeast-2"
        "awslogs-stream-prefix" = "ecs"
      }
    }
    },

    ],
    # Datadog Agent 사이드카 (MUL-148). 켤 때만 들어간다.
    # 로그는 앱과 같은 그룹, 접두사만 다르다
    [for c in [local.datadog_agent_container] : merge(c, {
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.wholesale.name
          "awslogs-region"        = "ap-northeast-2"
          "awslogs-stream-prefix" = "datadog"
        }
      }
    }) if var.enable_datadog],
  ))

  tags = { Name = "${local.prefix}-wholesale" }
}
