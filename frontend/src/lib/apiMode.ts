// 다른 모듈에서 import한 상수는 빌드에서 접히지 않는다. mock 코드·예시 데이터를 live 빌드에서 빼야 하는 자리는
// 이 값 대신 import.meta.env.VITE_API_MODE를 그 자리에서 비교한다(main.tsx, AuthPage).
export const apiMode =
  import.meta.env.VITE_API_MODE === 'live' ? 'live' : 'mock'
