#!/usr/bin/env bash
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "sudo로 실행해 주세요"; exit 1; }
IMAGE=${1:?사용법: setup-server.sh ghcr.io/<owner>/discordmoa (소문자)}

echo "== 타임존 =="
timedatectl set-timezone Asia/Seoul

echo "== 패키지 / Docker =="
export DEBIAN_FRONTEND=noninteractive
apt-get update -y && apt-get upgrade -y
apt-get install -y docker.io docker-compose-v2
systemctl enable --now docker
docker compose version

if systemctl list-unit-files | grep -q '^moa.service'; then systemctl disable --now moa || true; fi
if systemctl list-unit-files | grep -q '^mysql.service'; then systemctl disable --now mysql || true; fi

echo "== swap 2GB =="
if ! swapon --show | grep -q /swapfile; then
  fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
echo 'vm.swappiness=10' > /etc/sysctl.d/99-moa.conf && sysctl -p /etc/sysctl.d/99-moa.conf

echo "== 디렉터리 =="
install -d -m 755 /opt/moa /opt/moa/state
install -d -m 750 /etc/moa

ENV_FILE=/etc/moa/moa.env
if [[ ! -f $ENV_FILE ]]; then
  umask 077
  cat > "$ENV_FILE" <<EOF
# MOA 운영 환경변수 (root 600). 레포·채팅에 절대 올리지 말 것.
MOA_IMAGE=${IMAGE}

DISCORD_BOT_TOKEN=__FILL_ME__
DISCORD_GUILD_ID=__FILL_ME__
DISCORD_CONFIRM_BUTTON_ENABLED=true
DISCORD_ASK_BUTTON_ENABLED=false
DISCORD_OPS_CHANNEL_ID=
DISCORD_OPS_ROLE_ID=

DB_USERNAME=moa
DB_PASSWORD=$(openssl rand -hex 24)

GITHUB_APP_ID=__FILL_ME__
GITHUB_INSTALL_URL=__FILL_ME__

OPENAI_API_KEY=__FILL_ME__
EOF
  chmod 600 "$ENV_FILE"
  echo "env 파일 생성: $ENV_FILE (DB 비밀번호 자동 생성, 화면 출력 안 함)"
fi

echo "== 로그 용량 제한 =="
mkdir -p /etc/systemd/journald.conf.d
printf '[Journal]\nSystemMaxUse=300M\n' > /etc/systemd/journald.conf.d/moa.conf
systemctl restart systemd-journald

echo "== SSM Agent 상태 =="
snap services amazon-ssm-agent 2>/dev/null || systemctl status amazon-ssm-agent --no-pager || echo "SSM Agent가 안 보여요. DEPLOY.md 참고"

echo
echo "완료. 다음: env 채우기 → GitHub App 키 배치 → GHCR 로그인 → docker-compose.yml, deploy.sh 복사"
