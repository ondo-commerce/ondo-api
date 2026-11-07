#!/usr/bin/env bash
# 배포 DB 두 대로 가는 터널을 연다 (MUL-149).
#
#   ./tunnel.sh        Ctrl-C 로 닫는다
#
#   localhost:15432 → 도매 RDS
#   localhost:15433 → 소매 RDS
#
# 먼저 필요한 것:
#   - 터널 EC2 가 떠 있어야 한다  — cd infra && terraform apply -var enable_db_tunnel=true
#   - session-manager-plugin     — brew install --cask session-manager-plugin
#   - aws sso login --profile ondo
#
# 열어 둔 채로 다른 터미널에서 ./load.sh small deploy 를 돌린다.
set -euo pipefail
cd "$(dirname "$0")"

export AWS_PROFILE="${AWS_PROFILE:-ondo}"
export AWS_REGION="${AWS_REGION:-ap-northeast-2}"
infra="../../../infra"

out() { terraform -chdir="$infra" output -raw "$1" 2>/dev/null || true; }

instance="$(out db_tunnel_instance_id)"
if [[ -z "$instance" || "$instance" == "null" ]]; then
  echo "터널 EC2 가 없다 — cd infra && terraform apply -var enable_db_tunnel=true" >&2
  exit 1
fi
command -v session-manager-plugin >/dev/null || {
  echo "session-manager-plugin 이 없다 — brew install --cask session-manager-plugin" >&2
  exit 1
}

forward() { # <RDS 호스트> <로컬 포트>
  aws ssm start-session --target "$instance" \
    --document-name AWS-StartPortForwardingSessionToRemoteHost \
    --parameters "host=$1,portNumber=5432,localPortNumber=$2" >/dev/null &
}

# 하나만 닫히고 하나가 남지 않게, 이 스크립트가 끝나면 둘 다 내린다
trap 'kill $(jobs -p) 2>/dev/null || true' EXIT

forward "$(out wholesale_db_host)" 15432
forward "$(out retail_db_host)" 15433

for port in 15432 15433; do
  for _ in $(seq 30); do nc -z localhost "$port" 2>/dev/null && break; sleep 1; done
  nc -z localhost "$port" 2>/dev/null || { echo "localhost:$port 가 안 열렸다" >&2; exit 1; }
done

echo "터널 열림 — 도매 localhost:15432 · 소매 localhost:15433 (Ctrl-C 로 닫기)"
wait
