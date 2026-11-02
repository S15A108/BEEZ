import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, it } from 'vitest'
import type { BuildingDesign } from '@/stores/workspaceStore'
import { BuildingDesignPicker } from './BuildingDesignPicker'

function Picker() {
  const [design, setDesign] = useState<BuildingDesign>('modern')
  return <BuildingDesignPicker value={design} onChange={setDesign} />
}

// 프로젝트 만들기 수정안(SCR-04-v2-create-project): 카드마다 설명을 보이고, 고른 건물을 큰 미리 보기에 보인다.
it('건물 디자인을 고르면 미리 보기 이름과 설명이 바뀐다', async () => {
  render(<Picker />)
  expect(
    screen.getByRole('figure', { name: '선택한 건물 미리 보기: 모던 화이트' }),
  ).toBeInTheDocument()
  await userEvent.click(screen.getByRole('radio', { name: /목조 스튜디오/ }))
  const preview = screen.getByRole('figure', {
    name: '선택한 건물 미리 보기: 목조 스튜디오',
  })
  expect(preview).toHaveTextContent('나무 외벽 · 박공지붕 · 큰 창')
  expect(screen.getByRole('radio', { name: /목조 스튜디오/ })).toBeChecked()
})
