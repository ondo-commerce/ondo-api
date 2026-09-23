# ─────────────────────────────────────────────────────────────
# A · 네트워크 — VPC · 서브넷 6 · IGW · NAT · 라우팅
#
# 십의 자리가 층, 일의 자리가 AZ 다. 10.0.12.x 를 보면 「앱 · AZ 2번」이 바로 읽힌다.
# /24 로 넉넉히 잡은 건 서브넷은 만든 뒤에 크기를 못 바꾸고 사설 IP 는 공짜라서다.
# ─────────────────────────────────────────────────────────────

locals {
  # 환경 이름. 리소스 이름 앞에 붙어서 콘솔에서 한눈에 갈린다.
  # 나중에 운영 환경을 만들면 그쪽은 prod 가 된다 — 지금 안 붙여두면
  # ondo-vpc 가 둘이 돼서 어느 게 어느 환경인지 콘솔에서 구분이 안 된다.
  env    = "dev"
  prefix = "ondo-dev"

  vpc_cidr = "10.0.0.0/16"

  # AZ 순서를 여기서 고정한다. 아래 서브넷들이 전부 이 순서를 따르므로
  # 목록이 바뀌면 서브넷이 통째로 옮겨간다 — 그래서 데이터 소스를 그대로 쓰지 않고
  # 두 개만 잘라서 이름을 붙여 둔다.
  azs = slice(data.aws_availability_zones.available.names, 0, 2)

  public_cidrs = ["10.0.1.0/24", "10.0.2.0/24"]   # ALB · NAT
  app_cidrs    = ["10.0.11.0/24", "10.0.12.0/24"] # ECS 태스크
  db_cidrs     = ["10.0.21.0/24", "10.0.22.0/24"] # RDS

  # NAT 을 몇 개 둘지. 원래 모양은 AZ 마다 하나(length(local.azs))다.
  # 지금은 하나로 줄였다 — NAT 은 트래픽이 0 이어도 켜둔 시간만큼 돈을 받고,
  # 개당 월 4만원대다. 둘째 AZ 의 앱은 첫째 AZ 의 NAT 을 빌려 쓴다.
  #
  # 대신 잃는 것: 첫째 AZ 가 통째로 죽으면 양쪽 앱 모두 밖으로 못 나간다
  # (ECR 이미지 받기 · Secrets Manager 호출). 들어오는 요청은 ALB 가
  # 살아있는 쪽으로 보내므로 이미 떠 있는 태스크는 계속 응답한다.
  # 운영 환경을 만들 때는 length(local.azs) 로 되돌린다
  nat_count = 1
}

# 이 리전에서 실제로 쓸 수 있는 AZ 목록. 하드코딩하면 계정마다 다를 수 있다
data "aws_availability_zones" "available" {
  state = "available"
}

# ── VPC ──────────────────────────────────────────────────────
resource "aws_vpc" "main" {
  cidr_block = local.vpc_cidr

  # RDS 가 엔드포인트 이름을 쓰려면 둘 다 켜야 한다.
  # 끄면 프라이빗 서브넷에서 DB 주소를 못 푼다
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = { Name = "${local.prefix}-vpc" }
}

# ── 인터넷 게이트웨이 — 퍼블릭 서브넷의 바깥문 ──────────────────
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.prefix}-igw" }
}

# ── 퍼블릭 서브넷 · AZ 마다 하나 ──────────────────────────────
# 여기 들어가는 것: 외부 ALB, NAT 게이트웨이. 앱과 DB 는 안 들어온다
resource "aws_subnet" "public" {
  count = length(local.azs)

  vpc_id            = aws_vpc.main.id
  cidr_block        = local.public_cidrs[count.index]
  availability_zone = local.azs[count.index]

  # ALB 가 공인 IP 를 받아야 인터넷에서 닿는다
  map_public_ip_on_launch = true

  tags = { Name = "${local.prefix}-public-${count.index + 1}" }
}

# ── 앱 서브넷 · 프라이빗 ──────────────────────────────────────
# ECS 태스크가 여기 뜬다. 인터넷에서 직접 못 닿고 ALB 를 거쳐야만 들어온다.
# 나가는 건 NAT 로 나간다 — ECR 에서 이미지를 받고 Secrets Manager 를 부른다
resource "aws_subnet" "app" {
  count = length(local.azs)

  vpc_id            = aws_vpc.main.id
  cidr_block        = local.app_cidrs[count.index]
  availability_zone = local.azs[count.index]

  tags = { Name = "${local.prefix}-app-${count.index + 1}" }
}

# ── DB 서브넷 · 프라이빗 ──────────────────────────────────────
# RDS 전용. 나갈 일이 없어서 NAT 도 안 붙인다 — 라우팅 테이블을 따로 둔다
resource "aws_subnet" "db" {
  count = length(local.azs)

  vpc_id            = aws_vpc.main.id
  cidr_block        = local.db_cidrs[count.index]
  availability_zone = local.azs[count.index]

  tags = { Name = "${local.prefix}-db-${count.index + 1}" }
}

# ── NAT ───────────────────────────────────────────────────────
#
# ⚠️ 여기서부터 돈이 나간다. 켜두기만 해도 시간당 과금이고 나가는 데이터에도 붙는다.
#
# 개수는 local.nat_count 가 정한다. 왜 하나로 줄였는지는 거기 적어 뒀다.
resource "aws_eip" "nat" {
  count  = local.nat_count
  domain = "vpc"
  tags   = { Name = "${local.prefix}-nat-eip-${count.index + 1}" }
}

resource "aws_nat_gateway" "main" {
  count = local.nat_count

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  # IGW 가 먼저 있어야 NAT 가 밖으로 나갈 수 있다.
  # 참조 관계가 없어서 순서를 우리가 적어 준다
  depends_on = [aws_internet_gateway.main]

  tags = { Name = "${local.prefix}-nat-${count.index + 1}" }
}

# ── 라우팅 · 퍼블릭 ───────────────────────────────────────────
# 퍼블릭은 하나로 충분하다. 어느 AZ 든 나가는 문이 IGW 하나로 같다
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "${local.prefix}-rt-public" }
}

resource "aws_route_table_association" "public" {
  count = length(local.azs)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# ── 라우팅 · 앱 ───────────────────────────────────────────────
# AZ 마다 따로 둔다. NAT 이 AZ 마다 있으면 각자 자기 AZ 의 NAT 로 나가고,
# 지금처럼 하나뿐이면 둘 다 그 하나를 가리킨다 — % 가 그 일을 한다.
# 테이블 자체를 AZ 마다 두는 건 공짜라, nat_count 를 되돌리기만 하면
# 라우팅은 손대지 않고 AZ 별로 갈라진다
resource "aws_route_table" "app" {
  count = length(local.azs)

  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.main[count.index % local.nat_count].id
  }

  tags = { Name = "${local.prefix}-rt-app-${count.index + 1}" }
}

resource "aws_route_table_association" "app" {
  count = length(local.azs)

  subnet_id      = aws_subnet.app[count.index].id
  route_table_id = aws_route_table.app[count.index].id
}

# ── 라우팅 · DB ───────────────────────────────────────────────
# 바깥으로 나가는 길을 아예 안 만든다. VPC 안에서만 통한다.
# RDS 는 패치도 AWS 가 알아서 하므로 인터넷이 필요 없다
resource "aws_route_table" "db" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.prefix}-rt-db" }
}

resource "aws_route_table_association" "db" {
  count = length(local.azs)

  subnet_id      = aws_subnet.db[count.index].id
  route_table_id = aws_route_table.db.id
}
