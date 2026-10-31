# Nginx·HTTPS 런북

서버 호스트에 Nginx를 두고 Let's Encrypt 인증서로 HTTPS를 제공하는
절차입니다. 프론트 정적 파일 서빙과 수동 배포·롤백, 호스트 추가도 여기에
적습니다. 서버 기본 세팅(방화벽 80·443, 배포 디렉토리)은
[서버 세팅 런북](server-setup.md)을 먼저 마친 상태를 전제로 합니다.
(S15P31A108-122)

## 구성

| 요청 | 처리 |
|---|---|
| `http://dev.beez.ai.kr/.well-known/acme-challenge/*` | 인증서 발급용 토큰 파일 응답 |
| `http://dev.beez.ai.kr/*` | `301` → `https://dev.beez.ai.kr/*` |
| `https://dev.beez.ai.kr/*` | 프론트 `dist` 서빙(SPA fallback) |
| `http://jenkins.beez.ai.kr/*` | 인증 경로 외에는 `301` → HTTPS |
| `https://jenkins.beez.ai.kr/*` | Jenkins(`127.0.0.1:8080`) 리버스 프록시 ([Jenkins 런북](jenkins.md)) |
| 그 외 호스트·IP 직접 접속(HTTP) | `444` — 응답 없이 연결 종료 |
| 그 외 호스트·IP 직접 접속(HTTPS) | TLS 핸드셰이크 거절 |

- Nginx는 컨테이너가 아니라 호스트에 apt로 설치합니다. 인증서 갱신 후
  reload를 certbot 훅 한 줄로 처리할 수 있고, 프론트 결정 D13(서빙 서버가
  SPA fallback·`/api` 프록시 담당)과도 맞습니다.
- 설정의 정본은 저장소 `infra/nginx/`입니다. 서버에서 직접 고치지 않고
  저장소를 고쳐 반영합니다. certbot도 설정 파일을 수정하지 않도록
  `certonly --webroot`로만 씁니다.
- 호스트마다 설정 파일과 인증서를 따로 둡니다. 호스트를 추가하거나 운영
  도메인(`beez.ai.kr`)을 붙일 때 기존 호스트를 건드리지 않습니다.
- `dev.beez.ai.kr`, `jenkins.beez.ai.kr`의 A 레코드는 가비아 DNS에서
  `3.36.119.167`(이 서버)로 등록되어 있습니다.

### 저장소 파일과 서버 경로

| 저장소 | 서버 | 내용 |
|---|---|---|
| `infra/nginx/conf.d/security.conf` | `/etc/nginx/conf.d/` | `server_tokens off`(버전 숨김) |
| `infra/nginx/snippets/acme-challenge.conf` | `/etc/nginx/snippets/` | 인증 경로. 80번 server 블록마다 include |
| `infra/nginx/snippets/ssl-params.conf` | `/etc/nginx/snippets/` | TLS 1.2·1.3 등 공통 설정. 443번 server 블록마다 include |
| `infra/nginx/sites-available/00-default.conf` | `/etc/nginx/sites-available/` | 등록되지 않은 호스트 차단 |
| `infra/nginx/sites-available/dev.beez.ai.kr.conf` | `/etc/nginx/sites-available/` | dev 프론트 |
| `infra/nginx/sites-available/jenkins.beez.ai.kr.conf` | `/etc/nginx/sites-available/` | Jenkins 리버스 프록시 (S15P31A108-126) |

패키지가 소유한 `/etc/nginx/nginx.conf`는 고치지 않습니다. 고치면 nginx
패키지를 갱신할 때 설정 충돌 질문이 생깁니다.

## 1. Nginx 설치

```bash
sudo apt-get update
sudo apt-get install -y nginx
```

- Ubuntu 기본 저장소의 nginx(1.24)를 씁니다. 보안 패치는 Ubuntu가
  백포트하고 unattended-upgrades로 자동 적용됩니다.
- 설치 직후 80번에서 기본 페이지("Welcome to nginx")가 열립니다. 2단계에서
  기본 사이트를 끕니다.

확인:

```bash
nginx -v                          # nginx/1.24.0 (Ubuntu)
systemctl is-enabled nginx        # enabled
systemctl is-active nginx         # active
sudo ss -tlnp | grep nginx        # 0.0.0.0:80, [::]:80
```

nginx 프로세스는 master 1개(root)와 worker 4개(www-data)입니다.
`worker_processes auto`라 vCPU 수만큼 worker가 뜹니다.

## 2. 설정 반영

설정을 바꿀 때마다 같은 절차로 반영합니다.

로컬 PC(Git Bash):

```bash
scp -r /c/git/BEEZ/infra/nginx beez:/tmp/
```

서버:

```bash
sudo install -m 644 /tmp/nginx/conf.d/*.conf /etc/nginx/conf.d/
sudo install -m 644 /tmp/nginx/snippets/*.conf /etc/nginx/snippets/
sudo install -m 644 /tmp/nginx/sites-available/*.conf /etc/nginx/sites-available/
sudo nginx -t && sudo systemctl reload nginx
rm -r /tmp/nginx
```

- `/etc/nginx`는 root만 쓸 수 있어서 `/tmp`에 올린 뒤 `install`로 옮깁니다.
  `install -m 644`는 복사와 권한 지정을 한 번에 하고 소유자를 root로
  둡니다.
- `nginx -t`가 실패하면 `&&` 때문에 reload하지 않습니다. 돌아가는 설정은
  그대로 유지됩니다.
- `reload`는 연결을 끊지 않고 설정만 다시 읽습니다.

### 사이트 켜기·끄기(처음 한 번)

```bash
sudo ln -s /etc/nginx/sites-available/00-default.conf /etc/nginx/sites-enabled/
sudo ln -s /etc/nginx/sites-available/dev.beez.ai.kr.conf /etc/nginx/sites-enabled/
sudo rm /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

- Nginx는 `sites-enabled`의 링크만 읽습니다. 링크를 지우면 사이트가 꺼지고
  원본은 `sites-available`에 남습니다.
- `rm`으로 지우는 것은 기본 사이트의 링크뿐입니다. 되돌리려면
  `sudo ln -s /etc/nginx/sites-available/default /etc/nginx/sites-enabled/`.
- **새 서버에서는 `dev.beez.ai.kr.conf`를 켜기 전에 3단계의 인증서가 있어야
  합니다.** 443번 블록이 인증서 파일을 읽으므로 인증서가 없으면 `nginx -t`가
  `cannot load certificate`로 실패합니다. 순서는 아래 "새 서버에서 처음부터"를
  따릅니다.

## 3. 인증서 발급(certbot)

### 설치와 인증 경로

```bash
sudo apt-get install -y certbot
sudo mkdir -p /var/www/letsencrypt
```

- 인증 방식은 HTTP-01입니다. Let's Encrypt가
  `http://<호스트>/.well-known/acme-challenge/<토큰>`에 접속해 토큰 파일이
  있는지 봅니다. 80번이 열려 있어야 합니다.
- `--webroot` 방식이라 certbot은 `/var/www/letsencrypt`에 토큰 파일만 쓰고
  Nginx 설정은 건드리지 않습니다. nginx 플러그인(`python3-certbot-nginx`)은
  설치하지 않습니다.
- `acme-challenge.conf`의 `location ^~`는 HTTPS 리다이렉트보다 먼저
  매칭되므로 80번을 리다이렉트로 바꿔도 인증 경로는 유지됩니다.
- apt 설치 시 `certbot.timer`가 등록되어 하루 두 번 갱신을 검사하고, 만료
  30일 이내인 인증서만 갱신합니다.

인증 경로 확인(서버에서 시험 파일을 만들고 로컬 PC에서 받기):

```bash
# 서버
sudo mkdir -p /var/www/letsencrypt/.well-known/acme-challenge
echo ok | sudo tee /var/www/letsencrypt/.well-known/acme-challenge/test
# 로컬 PC
curl -s http://dev.beez.ai.kr/.well-known/acme-challenge/test      # ok
# 서버
sudo rm /var/www/letsencrypt/.well-known/acme-challenge/test
```

`sudo echo ok > 파일`은 실패합니다. `>`는 sudo가 아니라 현재 셸(ubuntu
권한)이 처리하므로, root로 실행되는 `tee`에 내용을 넘겨 씁니다.

### 발급

먼저 연습 서버(staging)로 시험합니다. 실서버는 실패가 반복되면 한동안
발급을 막습니다(호스트당 시간당 5회 실패).

```bash
sudo certbot certonly --webroot -w /var/www/letsencrypt -d dev.beez.ai.kr \
  -m <연락 메일> --agree-tos --no-eff-email --dry-run
# The dry run was successful.

sudo certbot certonly --webroot -w /var/www/letsencrypt -d dev.beez.ai.kr \
  -m <연락 메일> --agree-tos --no-eff-email \
  --deploy-hook "systemctl reload nginx"
# Successfully received certificate.
```

- `certonly`는 인증서만 받고 설정은 고치지 않습니다.
- `-d`에는 호스트 하나만 넣습니다. 호스트마다 인증서를 따로 받습니다.
- `-m`은 Let's Encrypt 계정 연락처입니다. Let's Encrypt는 2025년부터 만료
  안내 메일을 보내지 않으므로 만료 감시는 아래 갱신 확인으로 합니다.
- `--deploy-hook`은 갱신에 성공했을 때만 실행됩니다. Nginx는 인증서를 시작할
  때만 읽으므로 reload하지 않으면 옛 인증서를 계속 내보내다 만료됩니다. 이
  값은 갱신 설정에 저장되어 자동 갱신에도 적용됩니다.

### 갱신 확인

```bash
sudo certbot certificates          # 도메인·만료일·남은 일수
sudo grep -E 'authenticator|webroot_path|renew_hook' /etc/letsencrypt/renewal/dev.beez.ai.kr.conf
# authenticator = webroot / webroot_path = /var/www/letsencrypt, / renew_hook = systemctl reload nginx
sudo certbot renew --dry-run       # Congratulations, all simulated renewals succeeded
systemctl list-timers certbot.timer
```

`ssh beez 'sudo certbot renew --dry-run'`처럼 터미널 없이 실행하면 certbot이
갱신 전에 최대 8분까지 무작위로 기다립니다(갱신 요청 분산). 멈춘 것이
아닙니다. 서버에 접속해서 실행하면 바로 진행됩니다.

## 4. 프론트 수동 배포·롤백

CD(S15P31A108-123)가 생기기 전의 절차입니다. 이제 프론트는 backend·ai와
함께 [CD 런북](cd.md)의 배포 스크립트로 올리고, 손으로 배포·롤백할 때도 그
런북의 "손으로 배포·롤백"을 씁니다. 아래는 이미지 없이 로컬 빌드를 바로
올려야 할 때만 씁니다. 모두 로컬 PC의 Git Bash 한 창에서 실행합니다(`SHA`
변수를 이어서 씁니다).

```bash
cd /c/git/BEEZ/frontend
git status --short .              # 출력 없음 = 커밋된 코드 그대로 빌드
npm ci && npm run build           # ✓ built in ...
SHA=$(git rev-parse --short HEAD); echo "$SHA"
ssh beez "test ! -e /var/www/beez/releases/$SHA" && scp -r dist "beez:/var/www/beez/releases/$SHA"
ssh beez "ln -s /var/www/beez/releases/$SHA /var/www/beez/current.tmp && mv -T /var/www/beez/current.tmp /var/www/beez/current"
```

- 릴리스 폴더 이름은 커밋 SHA입니다. 어떤 코드가 떠 있는지 바로 알 수
  있습니다.
- `scp -r dist <대상>`은 대상 폴더가 없을 때 dist의 내용을 그 이름으로
  복사합니다. 이미 있으면 그 안에 `dist/`가 중첩되므로, 앞의 `test ! -e`로
  같은 SHA 폴더가 없을 때만 올립니다.
- `ssh beez "..."`는 큰따옴표라 `$SHA`가 로컬에서 값으로 바뀐 뒤 서버로
  전달됩니다.
- 심링크는 임시 링크를 만든 뒤 `mv -T`로 이름을 바꿔 교체합니다. 이름 변경은
  원자적이라 `current`가 없는 순간이 생기지 않습니다(`ln -sfn`은 삭제 후
  생성이라 그 사이 요청이 404를 받습니다). `-T`가 없으면 기존 `current`가
  가리키는 폴더 안으로 링크가 들어갑니다.
- Nginx는 요청마다 경로를 따라가므로 reload가 필요 없습니다.
- 파일 권한은 scp가 원본 권한을 가져와 `644`/`755`가 되고 www-data가 읽을
  수 있습니다.

### 롤백

```bash
ssh beez 'readlink /var/www/beez/current; ls -1t /var/www/beez/releases'   # 현재·최근 순 목록
PREV=<되돌릴 SHA>
ssh beez "ln -s /var/www/beez/releases/$PREV /var/www/beez/current.tmp && mv -T /var/www/beez/current.tmp /var/www/beez/current"
```

오래된 릴리스는 CD 배포 스크립트가 최근 5개만 남기고 지웁니다. 빌드 한
회차는 약 2MB입니다(2026-10-29, 처음 배포 때는 456KB).

## 5. 확인

로컬 PC(Git Bash):

```bash
curl -sI http://dev.beez.ai.kr/workspace/3 | grep -iE '^HTTP|^location'
# 301 Moved Permanently / Location: https://dev.beez.ai.kr/workspace/3

for p in / /workspace/3 /assets/nope.js; do
  MSYS_NO_PATHCONV=1 curl -s -o /dev/null -w "$p %{http_code} %{content_type}\n" "https://dev.beez.ai.kr$p"
done
# / 200 text/html
# /workspace/3 200 text/html      ← SPA fallback
# /assets/nope.js 404 text/html   ← 없는 asset은 index.html 대신 404

curl -sI https://dev.beez.ai.kr/ | grep -i cache-control          # no-cache
curl -s -o /dev/null http://k15a108.p.ssafy.io/; echo "exit=$?"   # exit=52 (444)
curl -s -o /dev/null https://k15a108.p.ssafy.io/; echo "exit=$?"  # exit=35 (핸드셰이크 거절)

echo | openssl s_client -connect dev.beez.ai.kr:443 -servername dev.beez.ai.kr -alpn h2 2>&1 \
  | grep -aE 'ALPN|Verify return code'
# ALPN protocol: h2 / Verify return code: 0 (ok)
```

- Git Bash는 Windows 프로그램에 넘기는 `/`로 시작하는 인자를 Windows 경로로
  바꿉니다(`/` → `C:/Program Files/Git/`). `-w` 형식 문자열처럼 경로가 아닌
  인자가 바뀌지 않게 `MSYS_NO_PATHCONV=1`을 붙입니다.
- Git for Windows의 curl은 HTTP/2를 지원하지 않아 `%{http_version}`이 1.1로
  나옵니다. HTTP/2는 `openssl s_client -alpn h2`나 브라우저로 확인합니다.
- Chrome 117부터 주소창에 자물쇠 대신 조절 아이콘이 나옵니다. 아이콘을 눌러
  "연결이 안전함"이 나오면 정상입니다.

서버에서 문제를 볼 때:

```bash
sudo tail -f /var/log/nginx/error.log    # 404·403의 실제 원인(파일 경로, 권한)
sudo tail -f /var/log/nginx/access.log
sudo tail /var/log/letsencrypt/letsencrypt.log
```

## 새 서버에서 처음부터

인증서가 없으면 443번 블록이 든 설정을 켤 수 없으므로 아래 순서로
진행합니다.

1. [서버 세팅 런북](server-setup.md) 1~5단계, DNS A 레코드가 새 서버 IP를
   가리키는지 확인(`nslookup dev.beez.ai.kr`)
2. 1단계 Nginx 설치
3. 2단계 설정 반영. 단, 사이트 켜기는 `00-default.conf`만 링크하고
   `default`를 지웁니다.
4. 인증 경로만 있는 임시 설정으로 dev 호스트를 엽니다.

   ```bash
   sudo tee /etc/nginx/sites-enabled/dev.beez.ai.kr.conf > /dev/null <<'EOF'
   server {
       listen 80;
       listen [::]:80;
       server_name dev.beez.ai.kr;
       include snippets/acme-challenge.conf;
   }
   EOF
   sudo nginx -t && sudo systemctl reload nginx
   ```

5. 3단계 certbot 설치·발급
6. 임시 설정을 지우고 저장소 설정을 링크합니다.

   ```bash
   sudo rm /etc/nginx/sites-enabled/dev.beez.ai.kr.conf
   sudo ln -s /etc/nginx/sites-available/dev.beez.ai.kr.conf /etc/nginx/sites-enabled/
   sudo nginx -t && sudo systemctl reload nginx
   ```

7. 4단계 프론트 배포, 5단계 확인

기존 서버의 `/etc/letsencrypt`를 그대로 옮기면 4~5번을 건너뛸 수 있지만,
새로 발급하는 편이 단순합니다.

## 호스트 추가

`jenkins.beez.ai.kr` 등 호스트를 추가할 때:

1. DNS A 레코드를 이 서버로 등록
2. `infra/nginx/sites-available/<호스트>.conf`를 만듭니다. 80번 블록에는
   `include snippets/acme-challenge.conf;`, 443번 블록에는
   `include snippets/ssl-params.conf;`와 그 호스트의 인증서 경로를 넣습니다.
3. "새 서버에서 처음부터"의 4~6번처럼 임시 설정 → 발급(`-d <호스트>`) →
   저장소 설정 링크 순서로 반영합니다.

운영 도메인(`beez.ai.kr`)은 운영 환경 결정(S15P31A108-73) 때 같은 방식으로
붙입니다.

## 주의

- **`add_header`는 location에 하나라도 있으면 server 블록의 `add_header`를
  상속하지 않습니다.** dev 설정은 location마다 `Cache-Control`을 넣었으므로,
  보안 헤더(HSTS 등)를 server 블록에 두면 조용히 빠집니다. 헤더를 추가할
  때는 snippet으로 만들어 각 location에서 include합니다. HSTS는 운영 환경
  결정 때 함께 적용합니다.
- `ssl_protocols`는 Ubuntu `nginx.conf`의 http 블록에도 있어서 `conf.d`에 또
  쓰면 duplicate 오류가 납니다. TLS 설정은 server 블록에서
  `ssl-params.conf`로 덮어씁니다.
- 80번을 연 직후부터 스캐너 접속이 들어옵니다(2026-10-23, 1시간 이내).
  `00-default.conf`가 없으면 IP로 들어온 요청에 첫 번째 사이트가 응답합니다.

## 2026-10-29 추가

`jenkins.beez.ai.kr`를 "호스트 추가" 절차로 열었습니다. certbot 계정이 이미 있어 `-m` 없이
발급했습니다. 인증서 발급자 Let's Encrypt `YE1`, 만료 2027-01-27,
`renew_hook = systemctl reload nginx`. 기존 dev 호스트는 그대로입니다.

## 2026-10-23 결과

| 항목 | 값 |
|---|---|
| nginx | 1.24.0 (Ubuntu) |
| certbot | 2.9.0 (apt) |
| 인증서 | `dev.beez.ai.kr`, ECDSA, 발급자 Let's Encrypt `YE2`, 만료 2027-01-21 |
| 갱신 | `renew --dry-run` 성공, `renew_hook = systemctl reload nginx` |
| 첫 배포 | `releases/97bb6f0`(dev `97bb6f0`), 456KB |
| 프로토콜 | HTTP/2(ALPN h2), TLS 1.2·1.3 |
