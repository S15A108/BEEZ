# GitHub → GitLab 동기화

GitHub `S15A108/BEEZ`가 기준 저장소이며 GitLab
`s15-final/S15P31A108`은 코드 사본입니다.

## 동작

- 브랜치 또는 태그를 GitHub에 Push하면 `Sync GitLab` Actions가 실행됩니다.
- 실행 시점의 모든 브랜치와 태그를 GitLab에 전달합니다.
- `workflow_dispatch`로 초기 동기화 또는 실패 후 재실행할 수 있습니다.
- 강제 Push, 브랜치·태그 삭제 동기화는 수행하지 않습니다.
- GitLab에 다른 이력이 있으면 덮어쓰지 않고 실패합니다.
- GitHub의 브랜치 삭제·rebase·태그 재지정은 수동 조정이 필요합니다.
- PR, 리뷰, Issues, Git LFS 객체는 이 워크플로로 복제되지 않습니다.
  LFS를 도입하면 별도 전송 단계를 추가해야 합니다.

## 인증 설정

1. GitLab 프로젝트 Settings → Access tokens에서 동기화 전용 토큰을
   생성합니다. `write_repository`만 선택하고 만료일을 설정합니다.
2. 최초 빈 저장소 초기화 및 기본 보호 브랜치 Push에 필요한 역할을
   확인합니다. 기본 설정에서는 Maintainer 역할이 필요할 수 있습니다.
   보호 브랜치의 쓰기 권한을 허용하지 않으면 동기화가 실패합니다.
3. GitHub BEEZ → Settings → Secrets and variables → Actions에서
   `GITLAB_SYNC_TOKEN` repository secret으로 저장합니다.
4. Actions에서 `Sync GitLab`를 수동 실행하고 성공 여부를 확인합니다.
5. 토큰 만료 전에 갱신하고 같은 Secret을 교체합니다.

토큰을 파일·커밋·채팅에 기록하지 않습니다. GitHub 저장소에 워크플로를
수정할 수 있는 사용자는 토큰을 사용할 수 있으므로 팀원 권한을 관리합니다.

## 운영

개발과 코드 리뷰는 GitHub에서 진행하고 GitLab에는 직접 커밋하지 않습니다.
성공한 실행 뒤 두 저장소의 대상 브랜치 커밋 SHA가 같아야 합니다.
네트워크·인증·보호 브랜치 오류는 Actions 로그에서 확인하고 원인을 수정한
뒤 수동 재실행합니다. 이 방식은 GitHub Push가 먼저 완료된 후 GitLab에
반영되므로 양쪽 Push가 동시에 성공하는 트랜잭션은 아닙니다.

Jira의 GitLab MR 병합 트리거는 코드 동기화만으로 발생하지 않습니다.
