// 3인칭 시점은 한 방향으로 고정한다(D42). 남동쪽 위에서 북서쪽 유리벽을 내려다본다.
const YAW = Math.atan2(22, 35)
const PITCH = Math.atan2(30, Math.hypot(22, 35))
const SIN_YAW = Math.sin(YAW)
const COS_YAW = Math.cos(YAW)
const SIN_PITCH = Math.sin(PITCH)
// 카메라는 높이 1.3 m를 겨눈다. 벽 위 3.6 m까지 담으려면 위로 2.3 m가 더 필요하다.
const WALL_ABOVE_AIM = 2.3
// 1은 방 전체가 화면에 꼭 차는 크기다. 그보다 멀리는 빠지지 않는다.
export const OFFICE_ZOOM = { min: 1, start: 1.25, max: 2 }
// 방 끝에서도 왼쪽·오른쪽 카드와 아래 단축 바 밖에 사람이 보이도록 화면의 이만큼은 방 밖을 비춰도 된다. 다 빼면 방 전체를 보는 시점이라 쓰지 않는다.
const OVERSCROLL = { x: 0.25, y: 0.15 }

type OfficeBounds = {
  W: number
  H: number
  footprint?: { left: number; back: number; right: number; front: number }
}

const roomSize = ({ W, H, footprint }: OfficeBounds) =>
  footprint
    ? {
        w: footprint.right - footprint.left,
        d: footprint.front - footprint.back,
      }
    : { w: W, d: H }

const roomOnScreen = (floor: OfficeBounds) => {
  const { w, d } = roomSize(floor)
  return {
    across: w * COS_YAW + d * SIN_YAW,
    ground: (w * SIN_YAW + d * COS_YAW) * SIN_PITCH,
  }
}

export function officeOrbitFrame(
  floor: OfficeBounds,
  width: number,
  height: number,
  zoom: number,
) {
  const { w, d } = roomSize(floor)
  const { across, ground } = roomOnScreen(floor)
  const sphereRadius = Math.hypot(w / 2, d / 2, 2.1)
  const distance = sphereRadius * 2.7 + 8
  const aspect = Math.max(1, width) / Math.max(1, height)
  const tall = ground + 2 * WALL_ABOVE_AIM * Math.cos(PITCH)
  const viewHeight = (Math.max(across / aspect, tall) * 1.05) / zoom
  const horizontal = distance * Math.cos(PITCH)
  return {
    left: (-viewHeight * aspect) / 2,
    right: (viewHeight * aspect) / 2,
    top: viewHeight / 2,
    bottom: -viewHeight / 2,
    far: distance + sphereRadius + 20,
    offset: {
      x: horizontal * SIN_YAW,
      y: distance * SIN_PITCH,
      z: horizontal * COS_YAW,
    },
  }
}

type OrbitFrame = ReturnType<typeof officeOrbitFrame>

/** 화면에서 끈 만큼 초점이 옮겨 가는 양(칸). 끈 곳이 커서를 따라오도록 초점은 반대로 움직인다. */
export function orbitPan(
  dx: number,
  dy: number,
  frame: OrbitFrame,
  height: number,
) {
  const perPixel = (frame.top - frame.bottom) / Math.max(1, height)
  const across = dx * perPixel
  const toward = (dy * perPixel) / SIN_PITCH
  return {
    x: -across * COS_YAW - toward * SIN_YAW,
    y: across * SIN_YAW - toward * COS_YAW,
  }
}

/** 초점(칸)이 방에서 멀어져 화면이 빈 곳만 비추지 않게 화면 기준으로 묶는다. */
export function orbitFocus(
  focus: { x: number; y: number },
  floor: OfficeBounds,
  frame: OrbitFrame,
  zoom: number,
) {
  const { across, ground } = roomOnScreen(floor)
  const closeness = Math.min(
    1,
    (zoom - OFFICE_ZOOM.min) / (OFFICE_ZOOM.start - OFFICE_ZOOM.min),
  )
  const center = floor.footprint
    ? {
        x: (floor.footprint.left + floor.footprint.right) / 2,
        y: (floor.footprint.back + floor.footprint.front) / 2,
      }
    : { x: floor.W / 2, y: floor.H / 2 }
  const viewWidth = frame.right - frame.left
  const viewHeight = frame.top - frame.bottom
  const limit = (room: number, view: number, overscroll: number) =>
    Math.max(0, (room - view) / 2 + view * overscroll * closeness)
  const clamp = (value: number, max: number) =>
    Math.max(-max, Math.min(max, value))
  const dx = focus.x - center.x,
    dy = focus.y - center.y
  const right = clamp(
    dx * COS_YAW - dy * SIN_YAW,
    limit(across, viewWidth, OVERSCROLL.x),
  )
  const toward =
    clamp(
      (dx * SIN_YAW + dy * COS_YAW) * SIN_PITCH,
      limit(ground, viewHeight, OVERSCROLL.y),
    ) / SIN_PITCH
  return {
    x: center.x + right * COS_YAW + toward * SIN_YAW,
    y: center.y - right * SIN_YAW + toward * COS_YAW,
  }
}
