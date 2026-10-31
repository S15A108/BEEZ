# 서버 세팅 런북

SSAFY EC2 `k15a108.p.ssafy.io`를 배포 가능한 상태로 만드는 절차입니다.
서버를 새로 받거나 다시 세팅할 때 위에서부터 순서대로 실행합니다.
(S15P31A108-121)

이 문서를 마친 뒤 Nginx·HTTPS와 프론트 배포는
[Nginx·HTTPS 런북](nginx-https.md)을 따릅니다.

## 서버 정보

| 항목 | 값 |
|---|---|
| 도메인 | `k15a108.p.ssafy.io` |
| OS | Ubuntu 24.04 LTS |
| 사양 | 4 vCPU · RAM 15GiB · 디스크 309GB |
| 접속 사용자 | `ubuntu` (sudo 그룹) |

SSAFY 서버는 ufw를 반드시 enable 상태로 유지해야 합니다. 포트를 열 때도
ufw를 끄지 않고 규칙만 추가합니다.

## 1. 패키지 갱신·타임존

```bash
sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a apt-get -y \
  -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold \
  full-upgrade
sudo apt-get -y autoremove
sudo timedatectl set-timezone Asia/Seoul
```

- `full-upgrade`를 씁니다. `upgrade`는 새 패키지가 필요한 갱신(새 커널
  버전 등)을 보류합니다.
- `--force-confold`는 설정 파일이 바뀐 패키지에서 기존 설정을 유지합니다.
  대화형 질문 없이 끝나게 하는 옵션입니다.
- `NEEDRESTART_MODE=a`는 갱신된 라이브러리를 쓰는 서비스를 자동으로
  재시작합니다.
- 타임존을 KST로 맞춰 로그 시각을 팀 기준과 같게 합니다. 시계 동기화는
  기본 설치된 chrony가 AWS Time Sync(`169.254.169.123`)로 처리합니다.

확인:

```bash
timedatectl                       # Time zone: Asia/Seoul, NTP synchronized: yes
cat /var/run/reboot-required      # 있으면 재부팅 필요
```

커널·libc가 갱신되면 `/var/run/reboot-required`가 생깁니다. 재부팅 전까지는
옛 커널로 동작하므로, 접속 중인 팀원에게 알린 뒤 `sudo reboot` 합니다.
재접속 후 `uname -r`로 새 커널 버전을 확인합니다.

로그인 안내에 `New release '26.04.x LTS' available`이 나와도
`do-release-upgrade`는 실행하지 않습니다. OS 메이저 버전 업그레이드는 되돌릴
수 없고 Docker·Jenkins 호환성이 바뀝니다. 24.04는 2029년까지 보안 업데이트를
받습니다.

참고: Ubuntu 24.04의 SSH는 소켓 활성화 방식이라 `systemctl is-active ssh`가
`inactive`로 나와도 정상입니다. `ssh.socket`이 `active`인지 확인합니다.

## 2. swap 2GB

```bash
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
grep -q "^/swapfile " /etc/fstab || echo "/swapfile none swap sw 0 0" | sudo tee -a /etc/fstab
echo "vm.swappiness=10" | sudo tee /etc/sysctl.d/99-swap.conf
sudo sysctl -p /etc/sysctl.d/99-swap.conf
```

- swap은 RAM이 가득 찼을 때 OOM killer가 프로세스(Jenkins·DB·백엔드)를
  강제 종료하기 전에 버티는 안전망입니다. 평소 사용을 전제로 하지 않습니다.
- RAM이 15GiB라 일시적인 메모리 급증만 흡수하면 되므로 2GB로 둡니다.
  swap이 크면 메모리 누수가 서버 정지 대신 느려짐으로만 나타나 발견이
  늦어집니다.
- `vm.swappiness=10`(기본 60)은 메모리가 실제로 부족할 때만 swap을 쓰게
  합니다. 디스크는 RAM보다 훨씬 느립니다.
- swapfile은 root만 읽을 수 있어야 하므로 권한을 600으로 둡니다. 메모리
  내용이 기록되는 파일입니다.

확인:

```bash
swapon --show                     # /swapfile file 2G
free -h                           # Swap: 2.0Gi
cat /proc/sys/vm/swappiness       # 10
```

swap 사용량이 계속 0이 아니면 메모리가 부족하다는 신호이므로 컨테이너별
메모리 사용량(`docker stats`)을 확인합니다.

## 3. 방화벽(ufw) 80·443

```bash
sudo ufw allow 80/tcp comment "http (certbot, https redirect)"
sudo ufw allow 443/tcp comment "https"
sudo ufw status verbose
```

- 기본 정책은 들어오는 연결 거부(`deny (incoming)`)이고, 허용한 포트만
  열립니다. 22는 SSAFY 기본 설정으로 이미 열려 있습니다.
- 80은 certbot 인증서 발급(HTTP-01 검증)과 HTTPS 리다이렉트에, 443은 실제
  서비스에 씁니다. 그 외 포트(Jenkins 8080, 백엔드·DB 등)는 열지 않고
  Nginx를 거쳐서만 접근합니다.
- 듣는 프로그램이 없는 포트는 열어도 연결 거부로 끝나므로, Nginx보다 먼저
  열어도 노출되는 것은 없습니다.

확인(로컬 PC에서):

```bash
# 거부(refused)가 바로 오면 방화벽 통과, 타임아웃이면 차단
timeout 8 bash -c 'exec 3<>/dev/tcp/k15a108.p.ssafy.io/80'
```

2026-10-23 결과: 80·443은 즉시 연결 거부(ufw·AWS 보안그룹 모두 통과),
8080은 타임아웃(차단).

서버 안에서 외부에 열린 포트 확인:

```bash
sudo ss -tlnp                     # 0.0.0.0 / [::] 에서 듣는 포트 = 외부 노출 후보
```

### 주의: Docker 포트 공개는 ufw를 우회한다

`docker run -p 8080:8080`이나 compose의 `ports: ["8080:8080"]`은 Docker가
iptables에 직접 규칙을 넣기 때문에 ufw의 deny가 적용되지 않습니다. ufw에서
닫혀 있어도 외부에 열립니다. 컨테이너 포트는 다음 중 하나로만 둡니다.

- 공개하지 않음: 같은 Docker 네트워크 안에서 서비스 이름으로 통신
- 로컬 전용: `ports: ["127.0.0.1:8080:8080"]`처럼 루프백에만 바인딩하고 Nginx가
  프록시

## 4. Docker

### 저장소 등록

```bash
sudo apt-get install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
gpg --show-keys --with-fingerprint /etc/apt/keyrings/docker.asc
# 지문이 9DC8 5822 9FC7 DD38 854A  E2D8 8D81 803C 0EBF CD88 인지 확인

echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] \
https://download.docker.com/linux/ubuntu \
$(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update
```

- Ubuntu 기본 저장소의 `docker.io` 대신 Docker 공식 저장소를 씁니다. 버전과
  compose v2·buildx 플러그인이 Docker 문서와 일치합니다.
- `signed-by`로 이 저장소의 패키지는 Docker GPG 키로 서명된 것만 받게
  합니다.

### 로그 크기 제한 후 설치

```bash
sudo install -m 0755 -d /etc/docker
sudo tee /etc/docker/daemon.json > /dev/null <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
JSON
sudo apt-get install -y docker-ce docker-ce-cli containerd.io \
  docker-buildx-plugin docker-compose-plugin
```

- Docker는 기본으로 컨테이너 로그를 무제한으로 쌓습니다. 컨테이너당
  10MB × 3개로 제한해 로그가 디스크를 채우지 않게 합니다.
- `daemon.json`을 설치 전에 두면 Docker가 처음 시작할 때부터 적용됩니다.
  나중에 바꾸면 `sudo systemctl restart docker`가 필요하고, 이미 만든
  컨테이너에는 적용되지 않습니다(재생성 필요).

### ubuntu를 docker 그룹에 추가

```bash
sudo usermod -aG docker ubuntu
# 다시 로그인해야 그룹이 적용됨
```

docker 그룹은 사실상 root 권한입니다(`docker run -v /:/host`로 호스트
전체에 접근 가능). 그룹에는 ubuntu와 jenkins(S15P31A108-126에서 추가)만 둡니다.
팀원 계정이나 다른 서비스 계정은 넣지 않습니다.

확인(새로 접속해서):

```bash
id -nG                            # docker 포함
docker ps                         # sudo 없이 성공
docker run --rm hello-world
docker compose version
docker info --format '{{.LoggingDriver}} {{.CgroupDriver}}'   # json-file systemd
```

2026-10-23 설치 버전: Docker Engine 29.9.2, Compose v5.6.1, buildx v0.38.1
(cgroup v2, overlayfs).

## 5. 배포 디렉토리

```bash
sudo mkdir -p /var/www
sudo install -d -o ubuntu -g ubuntu -m 755 /var/www/beez /var/www/beez/releases
sudo install -d -o ubuntu -g ubuntu -m 750 /opt/beez
```

| 경로 | 용도 | 권한 |
|---|---|---|
| `/var/www/beez` | 프론트 정적 파일(`dist`) | `2775 ubuntu:beez-deploy` — Nginx(`www-data`)가 읽고 jenkins가 씀 (처음 만들 때는 `755 ubuntu:ubuntu`, [Jenkins 런북](jenkins.md) 5단계에서 변경) |
| `/var/www/beez/releases/<sha>/` | 배포 회차별 빌드 결과 | 상동 |
| `/var/www/beez/current` | 서빙 중인 릴리스를 가리키는 심링크 | 첫 배포 때 생성 |
| `/opt/beez` | 서버 env 파일(`.env.<환경>`, 비밀값) | `2750 ubuntu:beez-deploy` — jenkins는 읽기만, 그 외 접근 불가 (처음 만들 때는 `750 ubuntu:ubuntu`, [CD 런북](cd.md) "서버 준비"에서 변경) |

- 배포는 `releases/<sha>`에 새 폴더를 만든 뒤 `current` 심링크를 바꾸는
  방식입니다. 파일을 덮어쓰는 도중의 화면이 노출되지 않고, 롤백은 심링크를
  이전 폴더로 되돌리면 됩니다.
- `current`는 가리킬 릴리스가 생기는 첫 배포 때 만듭니다. 미리 만들면 깨진
  링크가 됩니다.
- `install -d`는 디렉토리 생성·소유자·권한 지정을 한 번에 합니다.
- jenkins 계정의 쓰기 권한은 S15P31A108-126에서 `beez-deploy` 그룹으로
  열었습니다. 이후 `/var/www/beez`·`releases`는 `2775 ubuntu:beez-deploy`입니다
  ([Jenkins 런북](jenkins.md) 5단계).

확인:

```bash
ls -ld /var/www/beez /var/www/beez/releases /opt/beez
sudo -u www-data ls /var/www/beez          # releases 출력 = Nginx가 읽을 수 있음
touch /var/www/beez/releases/test && rm /var/www/beez/releases/test   # ubuntu 쓰기
```

## 6. 팀원 SSH 공개키 등록

### 원칙

- SSAFY가 준 pem 키(`K15A108T.pem`)는 서버의 최초·복구용 키입니다. 인프라
  담당만 보관하고 팀원에게 보내지 않습니다.
- 서버에 들어가야 하는 사람은 각자 ed25519 키쌍을 만들어 **공개키 한 줄만**
  보냅니다. 팀원용 안내는 노션 DEV GUIDE › 환경세팅 › 서버 접속 가이드에
  있습니다.
- 키에는 권한이 없고, 권한은 로그인하는 리눅스 사용자에 붙습니다. `ubuntu`는
  sudo를 쓸 수 있으므로 `ubuntu`에 공개키를 등록하는 것은 서버 관리자 권한을
  주는 것과 같습니다. 접속이 필요한 사람만 등록합니다.
- 서버는 공개키 인증만 허용합니다(`passwordauthentication no`).
- 인프라 담당도 평소에는 개인 키로 접속하고 pem은 복구할 때만 씁니다. 접속
  기록에 pem(RSA)이 찍히면 복구용 키가 쓰였다는 신호로 볼 수 있습니다.
  인프라 담당 PC의 `~/.ssh/config` 예:

  ```
  Host beez
      HostName k15a108.p.ssafy.io
      User ubuntu
      IdentityFile ~/.ssh/id_ed25519
      IdentitiesOnly yes

  Host beez-pem
      HostName k15a108.p.ssafy.io
      User ubuntu
      IdentityFile ~/.ssh/K15A108T.pem
      IdentitiesOnly yes
  ```

  `ssh -G beez | grep -i ^identityfile`로 적용될 키를 확인합니다.

### 등록

서버에서, 받은 공개키 한 줄을 임시 파일에 넣고 형식과 지문을 먼저 확인합니다.

```bash
cp ~/.ssh/authorized_keys ~/.ssh/authorized_keys.bak
echo 'ssh-ed25519 AAAA... 이름@beez' > /tmp/new.pub
ssh-keygen -lf /tmp/new.pub       # 256 SHA256:... 이름@beez (ED25519) 가 나와야 함
cat /tmp/new.pub >> ~/.ssh/authorized_keys
rm /tmp/new.pub
ssh-keygen -lf ~/.ssh/authorized_keys   # 등록된 키 전체 목록
```

- 공개키는 작은따옴표로 감쌉니다. 셸이 내용을 해석하지 않습니다.
- `ssh-keygen -lf`가 오류를 내면 복사 중 줄이 잘렸거나 개인키를 보낸
  것입니다. 개인키(`-----BEGIN OPENSSH PRIVATE KEY-----`)를 받았다면 등록하지
  않고, 그 키를 폐기하고 새로 만들어 달라고 요청합니다.
- **`>>`(덧붙이기)를 씁니다. `>`를 쓰면 pem 줄을 포함한 기존 내용이 지워져
  아무도 접속할 수 없게 됩니다.** 실수했으면 접속을 유지한 채로
  `cp ~/.ssh/authorized_keys.bak ~/.ssh/authorized_keys`로 되돌립니다.
- 등록 후 기존 접속을 닫지 말고, 당사자가 새로 접속되는지 확인합니다.

접속 기록에서 어떤 키로 들어왔는지 확인:

```bash
sudo journalctl -u ssh --since "1 hour ago" | grep Accepted
# Accepted publickey for ubuntu from <IP> ... ED25519 SHA256:<지문>
```

### 해제

```bash
cp ~/.ssh/authorized_keys ~/.ssh/authorized_keys.bak
grep -v ' 이름@beez$' ~/.ssh/authorized_keys.bak > ~/.ssh/authorized_keys
ssh-keygen -lf ~/.ssh/authorized_keys   # 해당 줄이 빠졌는지 확인
```

`grep -v`는 일치하는 줄을 뺀 나머지만 출력합니다. 주석(키 끝의 이름)이 같은
키가 둘이면 함께 지워지므로 지문 목록으로 결과를 확인합니다.

### 등록 표

등록·해제할 때 이 표를 함께 고쳐 PR로 남깁니다. 공개키 원문은 적지 않고
지문만 적습니다.

| 사람 | 키 주석 | 지문(SHA256) | 등록일 | 비고 |
|---|---|---|---|---|
| (SSAFY 발급 pem) | `K15A108T` | `gyxx69moGPDbCgcLE4raOFKPMFC897n+1h16OTdKKSk` (RSA 2048) | 서버 생성 시 | 복구용, 인프라 담당만 보관 |
| 전진 | `jin@beez` | `GXWeV8/5Ii2K5dimBqa++Rzt3IDINa9eMjJuuC6S4HI` (ED25519) | 2026-10-23 | 인프라 담당 일상 접속 |
| 변준성 | `junseong@beez` | `7+AklsyKb1BbSkoWVA1+EXk94jA06IHPz0bkrXnfKVg` (ED25519) | 2026-10-23 | |
| 양지훈 | `jihun@beez` | `a38FEGUfSAkJYz+lBpTgAUJ3Yu1MU4OFovCd5hVntU4` (ED25519) | 2026-10-23 | |
| 오서현 | `hyunneee@beez` | `wPxrYytLwezLKLmg1kdjPkyK+BI2BB3zXXz2k1aXfIg` (ED25519) | 2026-10-23 | |

공개키 지문은 비밀이 아닙니다. 공개키에서 계산되는 값이고, 공개키 자체가
공개를 전제로 합니다.

### 서버 호스트 지문

처음 접속할 때 `The authenticity of host ... can't be established` 질문에
나오는 지문이 아래와 같은지 확인한 뒤 `yes`를 입력합니다. 다르면 접속하지
않고 인프라 담당에게 알립니다.

| 종류 | 지문 |
|---|---|
| ED25519 | `SHA256:OIPnzEz0amRSPI5U1vhLMophrSV8pVebZK8mO1gCTfY` |
| ECDSA | `SHA256:O9Q5CYNYOzv4QZ/2Rww0AeplLy+nG5PFMb5qTbweqQU` |
| RSA | `SHA256:QFPdn7Rg2RWwSGG39A7KVwUtdtOyAzGuwKSVSANoD+Y` |

서버에서 확인: `for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf $f; done`
