#!/usr/bin/env bash
# 부하 테스트 데이터를 로컬 DB 두 대에 넣는다 (MUL-118).
#
#   ./load.sh small      도매처 50 · 상품 1천 · 소매처 100 · 주문서 1천
#   ./load.sh medium     도매처 200 · 상품 1만 · 소매처 500 · 주문서 1만
#   ./load.sh large      도매처 500 · 상품 10만 · 소매처 1천 · 주문서 10만
#
# 기본은 로컬이다. db/compose.yml 로 띄운 컨테이너에 넣는다.
#
#   ./load.sh small deploy   배포 DB 에 넣는다 (MUL-149)
#
# 배포 DB 는 프라이빗이라 ./tunnel.sh 를 다른 터미널에 열어 둬야 한다.
# 비밀번호는 RDS 가 만든 시크릿에서 그때그때 읽는다 — 파일이나 화면에 안 남는다.
#
# 앱을 먼저 한 번 띄워둬야 한다. 스키마는 Flyway 가 만든다.
# 다시 돌리면 이전 부하 데이터(id 1,000,000 이상)를 지우고 새로 넣는다.
set -euo pipefail

cd "$(dirname "$0")"
scale="${1:-small}"

python3 generate.py "$scale"

target="${2:-local}"

if [[ "$target" == "local" ]]; then
  echo "도매 DB 에 넣는 중..."
  docker exec -i ondo-wholesale-db psql -q -o /dev/null -U ondo -d ondo_wholesale < out/wholesale.sql

  echo "소매 DB 에 넣는 중..."
  docker exec -i ondo-retail-db psql -q -o /dev/null -U ondo -d ondo_retail < out/retail.sql

elif [[ "$target" == "deploy" ]]; then
  export AWS_PROFILE="${AWS_PROFILE:-ondo}"
  export AWS_REGION="${AWS_REGION:-ap-northeast-2}"
  infra="../../../infra"

  password() { # <terraform output 이름> → RDS 시크릿 JSON 의 password
    aws secretsmanager get-secret-value \
      --secret-id "$(terraform -chdir="$infra" output -raw "$1")" \
      --query SecretString --output text \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["password"])'
  }

  # 배포 DB 는 중간에 실패하면 멈춘다. 반쯤 들어간 데이터로 재면 결과를 못 믿는다
  push() { # <로컬 포트> <DB 이름> <시크릿 output> <SQL 파일>
    nc -z localhost "$1" 2>/dev/null || { echo "localhost:$1 이 닫혀 있다 — ./tunnel.sh 를 먼저 연다" >&2; exit 1; }
    PGPASSWORD="$(password "$3")" psql -q -o /dev/null -v ON_ERROR_STOP=1 \
      -h localhost -p "$1" -U ondo -d "$2" < "$4"
  }

  echo "배포 도매 DB 에 넣는 중..."
  push 15432 ondo_wholesale wholesale_db_secret_arn out/wholesale.sql

  echo "배포 소매 DB 에 넣는 중..."
  push 15433 ondo_retail retail_db_secret_arn out/retail.sql

else
  echo "두 번째 인자는 local 이나 deploy 다: $target" >&2
  exit 1
fi

echo "완료 — 로그인 load-r000001@ondo.test / ondo1234!"
