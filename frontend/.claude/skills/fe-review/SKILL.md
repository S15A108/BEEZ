---
name: fe-review
description: frontend 변경을 PR로 올리기 전에 하는 셀프 리뷰. 프론트 컨벤션, 테스트, 접근성, 성능, 보안, 불필요한 의존성, 문서 갱신을 점검하고 항목별 결과를 표로 보고한다. 리뷰만 하고 코드는 고치지 않는다.
---

# fe-review

## 대상

`dev`와 비교한 frontend 변경 전체(커밋되지 않은 변경 포함)를 본다.

```bash
git fetch origin
git diff origin/dev -- frontend/
```

검사 명령(lint, 테스트, 빌드)은 `fe-verify`가 맡는다. 이 스킬은 검사가 잡지 못하는 것을 사람 눈으로 보는 단계다.

## 점검 항목

| 영역   | 확인할 것                                                                                                             |
| ------ | --------------------------------------------------------------------------------------------------------------------- |
| 구조   | 파일이 README의 디렉터리 규칙대로 있는지. 한 기능에서만 쓰는 것이 공용 폴더에 올라가 있지 않은지                      |
| 데이터 | API 응답을 Zod로 파싱하는지. axios를 직접 import하지 않았는지. 서버 데이터를 Zustand나 `useState`에 복사하지 않았는지 |
| 폼     | React Hook Form + `zodResolver`인지. 검증 규칙이 스키마에만 있는지                                                    |
| UI     | shadcn에 있는 요소를 직접 만들지 않았는지. 임의 값 클래스(`bg-[#...]`)를 토큰 대신 쓰지 않았는지                      |
| 타입   | `any`, 근거 없는 `as` 단언, `@ts-ignore`, `@ts-expect-error`가 없는지                                                 |
| 테스트 | 새 로직과 폼에 단위 테스트가, 새 페이지에 E2E가 있는지. `it.skip`, `it.only`, `test.only`가 남지 않았는지             |
| 접근성 | 입력의 label, 이미지의 `alt`, 아이콘 버튼의 이름, 키보드로 조작할 수 있는지                                           |
| 성능   | `<Canvas>`와 무거운 페이지가 lazy인지. 큰 의존성이 새로 들어오지 않았는지                                             |
| 보안   | 토큰이나 키가 코드에 없는지. `VITE_` 변수에 비밀값이 없는지. `dangerouslySetInnerHTML`을 쓰지 않았는지                |
| 의존성 | 새 패키지를 기존 패키지나 브라우저 기능으로 대체할 수 없는지. README 스택 표를 갱신했는지                             |
| 문서   | README, `docs/decisions.md`를 고쳐야 하는 변경인지                                                                    |
| 정리   | `console.log`, 주석 처리된 코드, 설명 없는 TODO가 남지 않았는지                                                       |

빠르게 찾을 때는 다음 명령을 쓴다.

```bash
git diff origin/dev -- frontend/ | grep -nE "^\+.*(console\.log|: any|as any|@ts-ignore|\.only\(|\.skip\(|dangerouslySetInnerHTML)"
```

## 보고 형식

| 영역   | 결과 | 위치                             | 내용           |
| ------ | ---- | -------------------------------- | -------------- |
| 테스트 | 문제 | `src/features/task/TaskForm.tsx` | 폼 테스트 없음 |

- 결과는 `통과`, `주의`, `문제` 중 하나로 적는다.
- 통과한 영역은 한 줄로 묶어도 된다.
- 코드는 고치지 않는다. 마지막에 고칠지 사용자에게 묻는다.
