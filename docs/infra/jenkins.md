# Jenkins 런북

CD 배포 잡을 돌릴 Jenkins를 서버에 설치하고, `https://jenkins.beez.ai.kr`로만
접근하게 하는 절차입니다. 보안·계정 설정은 JCasC(Configuration as Code)로
저장소에 둡니다. 배포 잡은 S15P31A108-123에서 같은 JCasC 파일에 정의했습니다([CD 런북](cd.md)).
(S15P31A108-126)

[서버 세팅 런북](server-setup.md)과 [Nginx·HTTPS 런북](nginx-https.md)을
먼저 마친 상태를 전제로 합니다.

## 구성

| 항목 | 값 |
|---|---|
| 설치 | Jenkins LTS apt 패키지(systemd 서비스), Java 21(OpenJDK, Ubuntu 기본 저장소) |
| 받는 주소 | `127.0.0.1:8080`만. 외부 8080은 열지 않습니다 |
| 외부 접근 | `https://jenkins.beez.ai.kr` → 호스트 Nginx → `127.0.0.1:8080` |
| 계정 | Jenkins 자체 사용자 DB. 관리자 `beez-admin`, 배포 호출용 `github-actions`(S15P31A108-123). 가입 금지 |
| 권한 | matrix-auth. 관리자는 전부, `github-actions`는 전체 읽기 + `beez-deploy` 잡 보기·실행만. 비로그인은 아무것도 볼 수 없음 |
| 실행 계정 | `jenkins`(sudo 없음). `beez-deploy`·`docker` 그룹 |
| 설정 정본 | 저장소 `infra/jenkins/`, `infra/nginx/sites-available/jenkins.beez.ai.kr.conf` |

- 컨테이너가 아니라 apt로 설치합니다. 컨테이너로 돌리면 배포를 위해
  `docker.sock`과 호스트 경로를 붙여야 해서 사실상 root가 되고, 설치·갱신
  경로만 늘어납니다.
- 관리 화면이 인터넷에 직접 열리지 않도록 8080은 서버 자신에서만 받습니다.
  ufw도 8080을 막고 있지만 방화벽 하나에만 기대지 않습니다.
- 보안·계정 설정을 화면에서 클릭으로 만들면 서버 안에만 남아 재현할 수
  없습니다. JCasC 파일로 두면 복원이 "파일 복사 + 재시작"이 됩니다.

### 저장소 파일과 서버 경로

| 저장소 | 서버 | 내용 |
|---|---|---|
| `infra/jenkins/systemd/override.conf` | `/etc/systemd/system/jenkins.service.d/` | 127.0.0.1 바인딩, 설치 마법사 끄기, JCasC 경로, 비밀 파일 연결, `UMask=0002` |
| `infra/jenkins/casc/jenkins.yaml` | `/etc/jenkins/casc/` | 보안·계정·권한·공개 주소, 배포 잡 정의와 고정 파이프라인(Job DSL) |
| `infra/jenkins/issue-deploy-token.sh` | 서버에 두지 않음 | `github-actions` API 토큰 발급([CD 런북](cd.md)) |
| `infra/jenkins/plugins.txt` | 설치할 때만 사용 | 플러그인 목록(버전 고정) |
| `infra/nginx/sites-available/jenkins.beez.ai.kr.conf` | `/etc/nginx/sites-available/` | 리버스 프록시 |
| (저장소에 없음) | `/etc/jenkins/casc.env` | 관리자 ID·비밀번호, `github-actions` 비밀번호. `600 root:root` |

패키지가 설치한 `/usr/lib/systemd/system/jenkins.service`는 고치지 않습니다.
바꿀 값만 `jenkins.service.d/override.conf`로 덮어써서 패키지를 갱신해도
유지됩니다.

## 새 서버에서 처음부터

순서가 중요합니다. **apt 패키지는 설치하자마자 Jenkins를 켜고, 그때 설치
마법사가 `admin` 계정을 만듭니다.** JCasC는 목록에 적은 계정만 만들거나
고치고 목록에 없는 계정을 지우지 않으므로, 이 계정이 그대로 남습니다.
그래서 서비스를 먼저 막고(`mask`), 플러그인과 JCasC를 둔 뒤 처음 켭니다.

> **검증 상태:** 2026-10-29 서버는 설치 → 마법사 계정 삭제 순서로 세웠고,
> 아래 `mask` 순서는 아직 실제로 돌려 보지 않았습니다. 패키지 설치
> 스크립트(`jenkins.postinst`)는 서비스 시작 실패를 `|| true`로 무시하므로
> masked 상태에서도 설치는 끝나야 합니다. 그래도 `apt-get install jenkins`가
> 오류로 끝나면 아래처럼 마무리합니다.
>
> ```bash
> sudo systemctl unmask jenkins
> sudo dpkg --configure -a
> sudo systemctl stop jenkins     # 바로 멈춘다
> sudo ls /var/lib/jenkins/users  # 마법사 계정이 생겼는지 확인
> ```
>
> **`dpkg --configure -a`가 Jenkins를 켤 수 있습니다.** 이때는 마법사가 꺼져
> 있고 JCasC 플러그인은 아직 없어서 **보안이 전혀 없는 상태**로 뜹니다. 그래서
> 바로 멈추고, 4단계(플러그인) 뒤 6단계에서 `restart`로 다시 켭니다. 마법사
> 계정이 생겼으면 "주의"의 계정 삭제 명령으로 지웁니다. 처음 따라 해 본 사람이
> 결과를 이 문단에 고쳐 적습니다.

로컬 PC(Git Bash):

```bash
cd /c/git/BEEZ && scp infra/jenkins/plugins.txt infra/jenkins/casc/jenkins.yaml infra/jenkins/systemd/override.conf beez:/tmp/
```

서버:

### 1. 서비스 막기·systemd 설정

```bash
sudo systemctl mask jenkins
sudo mkdir -p /etc/systemd/system/jenkins.service.d
sudo install -m 644 /tmp/override.conf /etc/systemd/system/jenkins.service.d/override.conf
```

- `mask`는 서비스 파일을 `/dev/null`로 가려 어떤 방법으로도 켜지지 않게
  합니다. 패키지 설치 스크립트의 자동 시작도 막힙니다.

### 2. 관리자 비밀 파일

비밀번호는 비밀번호 관리자에서 새로 만들어 붙여 넣습니다. 화면과 셸 기록에
남지 않습니다. **영문·숫자와 `. _ ~ + = @ % -`만으로 20자 이상**으로
만듭니다(아래 "비밀번호 문자 제한").

```bash
sudo install -d -m 755 /etc/jenkins /etc/jenkins/casc
read -rsp "Jenkins admin password: " P && echo && \
  if [[ "$P" =~ ^[A-Za-z0-9._~+=@%-]{20,}$ ]]; then \
    printf 'JENKINS_ADMIN_ID=beez-admin\nJENKINS_ADMIN_PASSWORD=%s\n' "$P" | \
    sudo install -m 600 -o root -g root /dev/stdin /etc/jenkins/casc.env && echo saved; \
  else echo "허용 문자·길이가 아님. 저장하지 않음"; fi; unset P
sudo install -m 644 /tmp/jenkins.yaml /etc/jenkins/casc/jenkins.yaml
```

이어서 배포 호출용 `github-actions` 계정의 비밀번호를 넣습니다. 사람이 쓰지
않는 비밀번호라 무작위로 만들고 출력하지 않습니다. **이 줄이 없으면 JCasC가
경고만 남기고 `github-actions`를 빈 비밀번호로 만듭니다**(S15P31A108-123).

```bash
sudo grep -q '^JENKINS_DEPLOY_PASSWORD=' /etc/jenkins/casc.env || \
  printf 'JENKINS_DEPLOY_PASSWORD=%s\n' "$(openssl rand -hex 24)" | sudo tee -a /etc/jenkins/casc.env > /dev/null
sudo cut -d= -f1 /etc/jenkins/casc.env    # JENKINS_ADMIN_ID, JENKINS_ADMIN_PASSWORD, JENKINS_DEPLOY_PASSWORD
```

- `install … /dev/stdin`은 처음부터 `600 root:root`로 파일을 만듭니다. 만든
  뒤에 권한을 좁히는 방식과 달리 열려 있는 순간이 없습니다.
- `tee -a`는 파일 끝에 덧붙이기만 해서 권한(`600 root:root`)이 그대로입니다.
  `grep -q … ||`로 이미 있으면 건너뛰어 두 번 실행해도 값이 바뀌지 않습니다.
- `[[ … =~ … ]]`는 허용 문자·길이가 아니면 파일을 쓰지 않습니다.

### 비밀번호 문자 제한

systemd는 `EnvironmentFile`을 읽을 때 값을 그대로 넘기지 않습니다.
2026-10-29 이 서버에서 시험한 결과입니다.

| 파일에 쓴 값 | Jenkins가 받는 값 |
|---|---|
| `ab\cd` | `abcd` (역슬래시가 사라짐) |
| `  spaced  ` | `spaced` (앞뒤 공백이 잘림) |
| `p$q`, `x"y`, `it's` | 그대로 |

그래서 비밀번호에 역슬래시나 앞뒤 공백이 있으면 실제로 저장되는 비밀번호가
입력과 달라집니다. 이스케이프 규칙을 맞추는 대신 문자를 제한합니다.
영문·숫자만 써도 20자 이상이면 충분히 강합니다.
- systemd가 root로 이 파일을 읽어 Jenkins에 환경변수로 넘기므로 jenkins
  사용자는 파일을 읽을 권한이 없어도 됩니다.

### 3. Java·Jenkins 설치

```bash
sudo apt-get update
sudo apt-get install -y fontconfig openjdk-21-jre-headless
sudo curl -fsSL -o /etc/apt/keyrings/jenkins-keyring.asc \
  https://pkg.jenkins.io/debian-stable/jenkins.io-2026.key
gpg --show-keys /etc/apt/keyrings/jenkins-keyring.asc
# 5E386EADB55F01504CAE8BCF7198F4B714ABFC68 이 아니면 멈춘다
echo "deb [signed-by=/etc/apt/keyrings/jenkins-keyring.asc] https://pkg.jenkins.io/debian-stable binary/" \
  | sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null
sudo apt-get update
sudo apt-get install -y jenkins
```

- `debian-stable`은 LTS 판입니다. 매주 나오는 weekly(`debian`)보다 덜 자주
  바뀝니다.
- 서명 키는 해마다 바뀔 수 있습니다. 설치 전에
  `https://pkg.jenkins.io/debian-stable/`에서 키 파일 이름과 지문을 다시
  확인합니다. 2026-10 기준 키는 `jenkins.io-2026.key`(만료 2028-12-21)입니다.
- `-headless`는 GUI 라이브러리를 뺀 Java입니다. Jenkins가 요구하는 Java
  버전은 Jenkins의 Java 지원 정책을 따릅니다(2.580.x는 Java 21).

### 4. 플러그인 설치

```bash
cd /tmp
curl -fsSLO https://github.com/jenkinsci/plugin-installation-manager-tool/releases/download/2.15.0/jenkins-plugin-manager-2.15.0.jar
curl -fsSL https://github.com/jenkinsci/plugin-installation-manager-tool/releases/download/2.15.0/jenkins-plugin-manager-2.15.0.jar.sha256 \
  | awk '{print $1"  jenkins-plugin-manager-2.15.0.jar"}' | sha256sum -c -
sudo -u jenkins java -jar /tmp/jenkins-plugin-manager-2.15.0.jar \
  --war /usr/share/java/jenkins.war \
  --plugin-download-directory /var/lib/jenkins/plugins \
  --plugin-file /tmp/plugins.txt
```

- 화면의 "플러그인 설치" 대신 공식 도구와 목록 파일을 씁니다. 목록에 버전을
  고정해 두면 다음에도 같은 판이 깔립니다. 의존 플러그인은 도구가
  `--war`의 코어 버전에 맞춰 함께 받습니다.
- `sudo -u jenkins`로 받아야 파일 소유자가 jenkins가 됩니다. root로 받으면
  Jenkins가 나중에 플러그인을 갱신하지 못합니다.
- 플러그인은 공식 업데이트 센터에서 바로 받습니다. 이 서버는 SSAFY 가이드의
  미러 없이도 `updates.jenkins.io`에 닿습니다.

### 5. 배포 권한

```bash
sudo groupadd --system beez-deploy
sudo usermod -aG beez-deploy ubuntu
sudo usermod -aG beez-deploy,docker jenkins
sudo chgrp -R beez-deploy /var/www/beez
sudo chmod 2775 /var/www/beez /var/www/beez/releases
sudo find /var/www/beez/releases -mindepth 1 -type d -exec chmod 2775 {} +
sudo find /var/www/beez/releases -type f -exec chmod 664 {} +
sudo chmod 750 /var/lib/jenkins
```

- **`/var/lib/jenkins`(JENKINS_HOME)는 `750`으로 닫습니다.** 패키지는 `755`로
  두어서, Nginx 워커(`www-data`) 같은 다른 계정도 관리자 계정 파일의
  비밀번호 해시와 `secret.key`를 읽을 수 있습니다(2026-10-29 확인). 그 계정이
  뚫리면 해시를 가져가 비밀번호를 알아낼 수 있습니다. Nginx는 프록시만 해서
  이 폴더를 읽을 일이 없습니다. 패키지 설치 스크립트는 그룹·기타 권한을
  사용자에게 맡기므로(`jenkins.postinst` 주석) 패키지를 갱신해도 유지됩니다.
- 배포 잡은 `releases/<sha>`에 파일을 풀고 `current` 심링크를 바꿉니다.
  심링크를 바꾸는 임시 링크·`mv -T`가 `/var/www/beez` 안에서 일어나므로 그
  폴더에도 쓰기 권한이 필요합니다.
- 소유자를 jenkins로 바꾸지 않고 `beez-deploy` 그룹을 공유합니다. Jenkins가
  고장 나도 ubuntu로 수동 배포([Nginx·HTTPS 런북](nginx-https.md) 4단계)를
  그대로 할 수 있습니다.
- `2775`의 2는 setgid입니다. 안에 새로 만드는 파일이 `beez-deploy` 그룹을
  물려받습니다. systemd 설정의 `UMask=0002`와 함께, jenkins가 만든 릴리스도
  ubuntu가 지울 수 있습니다.
- `usermod`는 반드시 `-aG`로 씁니다. `-a`가 빠지면 기존 보조 그룹(ubuntu의
  sudo 등)이 지워집니다.
- **docker 그룹은 사실상 root 권한입니다.** GHCR에서 이미지를 받는 데
  필요해서 넣었습니다. 대신 Jenkins 화면은 관리자 1명·HTTPS 뒤에서만 열고,
  팀원 계정을 늘릴 때는 권한 전략(전원 관리자)부터 다시 정합니다.
- ubuntu의 새 그룹은 다시 접속해야 적용됩니다. jenkins는 다음 단계에서
  서비스를 켤 때 적용됩니다.
- `/opt/beez`(서버 env 파일)는 [CD 런북](cd.md) "서버 준비"에서
  `2750 ubuntu:beez-deploy`로 바꿉니다.

### 6. 처음 켜기

```bash
sudo systemctl unmask jenkins
sudo systemctl daemon-reload
sudo systemctl enable jenkins
sudo systemctl restart jenkins
sleep 30
sudo journalctl -u jenkins --since "-2min" --no-pager | grep -E "SEVERE|Jenkins is fully up"
rm /tmp/plugins.txt /tmp/jenkins.yaml /tmp/override.conf /tmp/jenkins-plugin-manager-2.15.0.jar
```

- `enable --now`가 아니라 `restart`로 켭니다. `enable --now`는 이미 돌고 있는
  서비스를 다시 시작하지 않습니다. 설치 중 어떤 이유로 Jenkins가 먼저
  켜졌다면 플러그인·JCasC 없이 뜬 프로세스가 그대로 남습니다. `restart`는
  꺼져 있으면 켜고, 켜져 있으면 다시 시작해 새 설정을 반드시 읽게 합니다.
- `Jenkins is fully up and running`이 나오면 됩니다. `SEVERE`가 있으면 아래
  "주의"의 JCasC 항목을 봅니다.

**7단계(외부 노출) 전에 접근 제어를 반드시 확인합니다.** 셋 중 하나라도
다르면 HTTPS를 열지 않습니다.

```bash
curl -s -o /dev/null -w 'anon %{http_code}\n' http://127.0.0.1:8080/          # 403
curl -s -o /dev/null -w 'signup %{http_code}\n' http://127.0.0.1:8080/signup   # 404
sudo ls /var/lib/jenkins/users                                                # beezadmin_…·githubactions_… 두 개
```

### 7. Nginx·HTTPS

[Nginx·HTTPS 런북](nginx-https.md)의 "호스트 추가" 절차대로
`jenkins.beez.ai.kr`를 엽니다. 임시 80 설정 → `certbot certonly --webroot
-d jenkins.beez.ai.kr` → 저장소 설정 링크 순서입니다. 보안 설정(6단계)이
끝난 뒤에 엽니다.

## 확인

서버:

```bash
systemctl is-active jenkins                                     # active
sudo ss -ltnp | grep 8080                                       # 127.0.0.1:8080만
systemctl show jenkins -p UMask --value                         # 0002
grep Groups /proc/$(systemctl show jenkins -p MainPID --value)/status   # jenkins·beez-deploy·docker
sudo ls /var/lib/jenkins/users                                  # beezadmin_…·githubactions_… 두 개
stat -c '%a %U:%G' /var/lib/jenkins                             # 750 jenkins:jenkins
sudo -u www-data ls /var/lib/jenkins 2>&1 | head -1             # Permission denied
sudo -u jenkins docker version --format '{{.Server.Version}}'   # sudo 없이 docker 사용
sudo -u jenkins sudo -n true                                    # 거부되어야 함
```

로컬 PC:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://jenkins.beez.ai.kr/login    # 200
curl -s -o /dev/null -w '%{http_code}\n' https://jenkins.beez.ai.kr/         # 403 (로그인 강제)
curl -s -o /dev/null -w '%{http_code}\n' https://jenkins.beez.ai.kr/signup   # 404 (가입 금지)
curl -s -m 8 -o /dev/null -w '%{http_code}\n' http://jenkins.beez.ai.kr:8080/  # 000 (닫힘)
for i in $(seq 1 15); do curl -s -o /dev/null -w '%{http_code} ' -u nobody:wrong https://jenkins.beez.ai.kr/api/json; done; echo   # 401 … 뒤 429 (대입 제한)
```

마지막 줄은 일부러 틀린 계정으로 연달아 요청해 대입 제한이 걸리는지 봅니다.
**이 PC의 IP가 잠시(1분 안팎) 인증 요청에 429를 받습니다.** 로그인은 그 뒤에
합니다.

API 토큰 요청도 같은 한도(IP당 분당 10회, 순간 10회)를 받습니다. API 토큰과
비밀번호가 둘 다 `Authorization: Basic`으로 와서 Nginx가 구분할 수 없기
때문입니다. API 한도만 늘리면 비밀번호 대입 한도도 함께 늘어납니다. 지금 API를
쓰는 곳은 Actions의 배포 잡 호출뿐이고, 배포 1회당 1요청이라 이 한도 안에
듭니다(S15P31A108-123).

브라우저로 `https://jenkins.beez.ai.kr`에 `beez-admin`으로 로그인되는지 봅니다.

## 백업·복원

Jenkins를 다시 세우는 데 필요한 것은 대부분 저장소에 있습니다.

| 무엇 | 어디에 | 복원 방법 |
|---|---|---|
| 서비스·보안·계정 설정 | 저장소 `infra/jenkins/` | "새 서버에서 처음부터" 그대로 |
| 관리자 비밀번호 | 비밀번호 관리자 | 2단계에서 다시 입력 |
| 플러그인 | 저장소 `plugins.txt` | 4단계 |
| 배포 잡 정의·파이프라인 | 저장소 `casc/jenkins.yaml`(Job DSL, 파이프라인 고정) | 켤 때마다 JCasC가 잡을 다시 만듦 |
| `github-actions` API 토큰 | JENKINS_HOME(해시만 저장) | 백업에 없으면 [CD 런북](cd.md) "토큰 발급·교체"로 새로 발급 |
| 빌드 기록·자격 증명·잡 설정 | `/var/lib/jenkins`(JENKINS_HOME) | 아래 백업 파일 |

JENKINS_HOME은 빌드 기록, 사용자별 API 토큰(-123의 배포 호출 계정 등), 화면에서
넣은 자격 증명을 담습니다. 자격 증명은 `secrets/master.key`로 암호화되어 있어 **`secrets`
폴더와 함께 옮겨야 풀립니다.** 이 폴더가 빠지면 자격 증명을 다시 넣어야
합니다.

### 백업

**서버에 백업 파일을 만들지 않고** 압축 결과를 ssh로 바로 로컬 PC에 받습니다.
root가 `/tmp`에 파일을 만들면 기본 umask(022) 때문에 `644`로 생겨, 권한을
좁히기 전까지 서버의 다른 사용자가 `master.key`가 든 파일을 읽을 수 있습니다
(2026-10-29 확인).

로컬 PC(Git Bash):

```bash
mkdir -p ~/beez-backup && F=~/beez-backup/jenkins-home-$(date +%Y%m%d-%H%M).tgz
ssh beez 'sudo systemctl stop jenkins && sudo tar --exclude=workspace --exclude=caches --exclude=.cache -czf - -C /var/lib jenkins; rc=$?; sudo systemctl start jenkins || { echo "jenkins restart FAILED" >&2; [ $rc -eq 0 ] && rc=90; }; exit $rc' \
  > "$F.part"; rc=$?
if { [ $rc -eq 0 ] || [ $rc -eq 90 ]; } && tar -tzf "$F.part" > /dev/null; then
  mv "$F.part" "$F" && echo "saved $F"
else
  echo "backup FAILED"; rm -f "$F.part"
fi
[ $rc -eq 90 ] && echo "Jenkins가 다시 켜지지 않았습니다. 서버에서 journalctl -u jenkins -n 50 확인"
```

- `tar -czf -`는 압축 결과를 파일 대신 표준 출력으로 내보내고, ssh가 그대로
  로컬 파일로 받습니다. 서버 디스크에는 아무것도 남지 않습니다.
- 받는 중에는 `.part` 이름으로 쓰고, 압축 파일이 온전한지(`tar -tzf`) 확인한
  뒤에만 제 이름으로 바꿉니다. 실패하면 `.part`만 지우므로 **이전 백업을 빈
  파일로 덮어쓰지 않습니다.** 파일 이름에 시각까지 넣어 같은 날 두 번 받아도
  겹치지 않습니다.
- 서버 쪽은 `tar`의 종료 코드를 `rc`에 담아 두고, Jenkins를 다시 켠 뒤 그
  코드로 끝냅니다. 백업이 실패해도 Jenkins는 켜지고, 실패는 PC 쪽에
  `backup FAILED`로 드러납니다.
- Jenkins가 다시 켜지지 않으면 종료 코드 `90`으로 따로 알립니다. `tar`(0~2)나
  ssh(255)와 겹치지 않는 값입니다. 압축이 온전하면 백업 파일은 그대로 남기고
  마지막 줄에 재시작 실패를 출력합니다. 백업은 됐는데 Jenkins가 꺼진 채로
  남는 경우를 `saved`만 보고 지나치지 않기 위해서입니다.
- 멈추고 백업해야 설정 파일이 쓰이는 도중에 잘리지 않습니다. 배포 잡이 돌고
  있지 않은 때에 합니다.
- `workspace`·`caches`는 다시 만들 수 있어 뺍니다. 2026-10-29 기준 약
  17MB입니다.
- 이 파일에는 `secrets/master.key`가 들어 있습니다. 저장소·메신저·공유
  폴더에 올리지 않습니다. 로컬 PC가 Windows라면 Git Bash의 `chmod`는 NTFS 권한을
  바꾸지 못하므로 폴더 권한으로 보호된다고 기대하지 않습니다. 사용자 프로필
  폴더(`~`) 안에 두면 기본 ACL상 본인과 관리자만 읽을 수 있고, 다른 곳으로
  옮길 때는 압축 파일 자체를 암호화합니다.

### 복원

"새 서버에서 처음부터"의 **1~3단계와 5단계**를 마친 뒤 복원합니다.
**4단계(플러그인 설치)는 건너뜁니다.** 백업에 그때의 플러그인이 들어 있어서,
4단계에서 새로 받은 플러그인과 섞이면 버전이 어긋난 파일이 남습니다. 혹시 4단계를
했더라도 아래 명령이 플러그인 폴더를 먼저 비우고 풉니다. 6단계(처음 켜기) 대신
로컬 PC에서 백업을 ssh로 바로 풀어 넣습니다. 서버 `/tmp`에 올리지 않는 이유는
백업과 같습니다.

```bash
F=~/beez-backup/jenkins-home-YYYYMMDD-HHMM.tgz
if tar -tzf "$F" > /dev/null && \
   ssh beez 'sudo systemctl stop jenkins 2>/dev/null; sudo rm -rf /var/lib/jenkins/plugins && sudo tar -xzf - -C /var/lib && sudo chown -R jenkins:jenkins /var/lib/jenkins && sudo chmod 750 /var/lib/jenkins' < "$F"; then
  ssh beez 'sudo systemctl unmask jenkins && sudo systemctl daemon-reload && sudo systemctl enable jenkins && sudo systemctl restart jenkins' \
    && echo "restored" || echo "풀기는 됐지만 Jenkins 시작 실패. 서버에서 journalctl -u jenkins -n 50 확인"
else
  echo "복원 FAILED: Jenkins를 켜지 않았습니다"
fi
```

**풀기가 성공했을 때만 Jenkins를 켭니다.** 새 서버에서 풀기가 중간에 실패하면
`config.xml`과 JCasC 플러그인이 없는 상태입니다. 1단계에서 설치 마법사도
꺼 두었으므로, 이대로 켜면 로그인 없이 누구나 관리할 수 있는 Jenkins가
뜹니다(127.0.0.1 한정이라도). 실패하면 서비스가 1단계의 `mask` 상태로 남아
실수로 켜지지도 않습니다. 원인을 고친 뒤 같은 명령을 다시 실행합니다.

켠 뒤에는 6단계의 접근 제어 확인을 똑같이 합니다. 새 서버의 Jenkins 본체가
백업 때보다 새 버전이면 플러그인 호환 경고가 뜰 수 있습니다. 그때는 Jenkins를
멈추고 4단계 명령으로 `plugins.txt`를 다시 설치해 맞춥니다.

JCasC는 켤 때마다 다시 적용되므로, 백업 이후 바뀐 보안 설정은 저장소 값으로
맞춰집니다.

## 비밀번호 바꾸기

```bash
read -rsp "new password: " P && echo && \
  if [[ "$P" =~ ^[A-Za-z0-9._~+=@%-]{20,}$ ]]; then \
    REST=$(sudo grep -v '^JENKINS_ADMIN_PASSWORD=' /etc/jenkins/casc.env) && \
    grep -q '^JENKINS_ADMIN_ID=' <<< "$REST" && grep -q '^JENKINS_DEPLOY_PASSWORD=' <<< "$REST" && \
    printf '%s\nJENKINS_ADMIN_PASSWORD=%s\n' "$REST" "$P" | sudo install -m 600 -o root -g root /dev/stdin /etc/jenkins/casc.env && \
    sudo systemctl restart jenkins && echo changed || echo "실패: casc.env 읽기·항목(ID·DEPLOY) 또는 재시작을 확인"; \
  else echo "허용 문자·길이가 아님. 바꾸지 않음"; fi; unset P REST
```

허용 문자는 2단계의 "비밀번호 문자 제한"과 같습니다.

- **관리자 비밀번호 줄만 바꾸고 나머지 줄은 그대로 둡니다.** 파일을 통째로
  새로 쓰면 `JENKINS_DEPLOY_PASSWORD`가 지워지고, JCasC가 `github-actions`를
  빈 비밀번호로 다시 만듭니다(-123 이전 명령은 파일 전체를 다시 썼습니다).
- 나머지 줄을 먼저 변수에 담은 뒤 씁니다. 같은 파일을 읽으면서 바로 쓰면
  다 읽기 전에 파일이 비워질 수 있습니다.
- **읽기가 실패하면 쓰지 않습니다.** `sudo grep`이 실패하거나(인증 실패, 경로
  오타) 읽은 내용에 `JENKINS_ADMIN_ID`·`JENKINS_DEPLOY_PASSWORD`가 없으면 거기서
  멈춥니다. 그냥 이어 쓰면 비밀번호 한 줄만 남은 파일로 덮어써 관리자 ID까지
  사라집니다.

JCasC가 켤 때 `casc.env`의 값으로 비밀번호를 다시 씁니다. 화면에서 바꾼
비밀번호는 재시작하면 이 파일 값으로 돌아갑니다.

## 주의

- **JCasC는 모르는 항목이 하나라도 있으면 Jenkins 기동 자체를 멈춥니다**
  (`UnknownAttributesException`). 설정 반만 적용된 채 뜨는 것보다 안전한
  동작입니다. 인터넷의 예전 예제를 옮길 때는 지금 버전에 있는 항목인지
  확인합니다. 2026-10-29에 `crumbIssuer.standard.excludeClientIPFromCrumb`로
  실제로 기동이 실패했습니다. CSRF 방어는 현재 코어에서 항상 켜져 있어 적지
  않습니다.
- 설치 마법사가 만든 계정이 남았으면 `beez-admin`으로 API를 불러 지웁니다.
  POST 요청이라 CSRF 토큰(crumb)을 먼저 받아 같은 세션으로 보냅니다.

  ```bash
  read -rsp "beez-admin password: " P && echo && \
    CRUMB=$(curl -s -c /tmp/jcj -u "beez-admin:$P" http://127.0.0.1:8080/crumbIssuer/api/json \
      | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["crumbRequestField"]+":"+d["crumb"])') && \
    curl -s -b /tmp/jcj -u "beez-admin:$P" -H "$CRUMB" -X POST -o /dev/null -w '%{http_code}\n' \
      http://127.0.0.1:8080/user/admin/doDelete    # 302
  unset P CRUMB; rm -f /tmp/jcj
  sudo rm -f /var/lib/jenkins/secrets/initialAdminPassword
  ```

- 연속으로 기동에 실패하면 systemd가 더는 다시 켜지 않습니다. 설정을 고친
  뒤 `sudo systemctl reset-failed jenkins`로 기록을 지우고 켭니다.
- 화면에서 바꾼 보안 설정은 재시작하면 JCasC 값으로 돌아갑니다. 바꿀 때는
  저장소 파일을 고쳐 반영합니다.
- 응답 헤더 `X-Jenkins`에 버전이 보입니다. Jenkins CLI 등이 이 헤더로
  서버를 알아보므로 숨기지 않았습니다. 버전 노출이 문제가 되면 Nginx에서
  `proxy_hide_header`로 지웁니다.
- SSAFY Jenkins 가이드는 8080을 Anywhere로 열지만 이 서버는 열지 않습니다.
  `authorized_keys`는 `>>`로 추가만 하고 기존 pem 줄을 지우지 않는다는
  가이드 주의는 그대로 지킵니다([서버 세팅 런북](server-setup.md) 6단계).

## 2026-10-29 결과

| 항목 | 값 |
|---|---|
| Jenkins | 2.580.2 (LTS, apt) |
| Java | OpenJDK 21.0.12 (`openjdk-21-jre-headless`) |
| 플러그인 | `configuration-as-code` 2140.v00e72b_047734 + 의존 17개. -123에서 `matrix-auth`·`job-dsl`·파이프라인 5개 추가(새로 깔면 의존 포함 37개). `git`·`workflow-multibranch`는 파이프라인을 고정하며 뺐고 서버에서도 지웠습니다 |
| 인증서 | `jenkins.beez.ai.kr`, 발급자 Let's Encrypt `YE1`, 만료 2027-01-27, `renew_hook = systemctl reload nginx` |
| 외부 확인 | `/login` 200, `/` 403, `/signup` 404, `http` → 301 `https`, 8080 닫힘 |
| 대입 제한 | 틀린 Basic 인증 11회까지 401 뒤 429, 틀린 로그인 폼 6회까지 302 뒤 429. 비인증 화면은 영향 없음 |
| 권한 | `/var/lib/jenkins` 750, `www-data`는 Permission denied |
| keepalive | Nginx → 8080 연결이 요청 뒤에도 유지(재사용) |
| JENKINS_HOME | 53MB, 백업 압축 약 17MB |
| 메모리 | 기동 후 사용 1.2GiB / 15GiB |

설치 마법사가 처음 기동 때 만든 `admin` 계정은 JCasC 적용 뒤에도 남아 있어
API로 지우고 `secrets/initialAdminPassword`도 지웠습니다. 위 "새 서버에서
처음부터" 순서는 이 계정이 생기지 않도록 고친 순서입니다.
