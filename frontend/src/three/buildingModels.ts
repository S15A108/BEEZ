// 건물 디자인 값 → Kenney City Kit 건물(D22). three를 부르지 않아 2D 화면에서도 가볍게 쓴다.
// 도시 화면, 산책 화면의 우리 건물, 고르기 카드 그림(같은 모델에 같은 색을 곱해 렌더)이 이 표를 따른다.
// tint는 모델 재질 색에 곱하는 색이다(Kenney 건물은 대부분 흰·회색이라 벽돌·나무·짙은 외벽을 색으로 낸다).
export const KENNEY_BUILDINGS: Record<
  string,
  { model: string; tint?: string }
> = {
  glass: { model: 'building-skyscraper-a' },
  brick: { model: 'building-j', tint: '#f0b49a' },
  modern: { model: 'building-i' },
  tower: { model: 'building-skyscraper-c', tint: '#8f8f99' },
  wood: { model: 'building-e', tint: '#ecc996' },
}

export const kenneyBuildingOf = (design?: string) =>
  KENNEY_BUILDINGS[design ?? ''] ?? KENNEY_BUILDINGS.modern

export const kenneyBuildingUrl = (model: string) =>
  `/models/kenney/city/${model}.glb`
