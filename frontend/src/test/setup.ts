import '@testing-library/jest-dom/vitest'
import { cleanup, configure } from '@testing-library/react'
import { afterEach } from 'vitest'

// findBy·waitFor 기본 대기 1초는 병렬 부하에서 모자란다. 기다리던 것이 오면 바로 끝난다(D44).
configure({ asyncUtilTimeout: 3_000 })

afterEach(() => cleanup())
