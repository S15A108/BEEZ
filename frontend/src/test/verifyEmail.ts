import { authApi } from '@/api/services'
import { sentCode } from '@/mocks/emailVerifications'

/** 목 어댑터로 코드를 받고 확인해 가입에 쓸 verified_token을 얻는다(ACC-A11·A12). */
export async function verifyEmail(email: string) {
  const { verification_id } = await authApi.sendEmailVerification({ email })
  const { verified_token } = await authApi.confirmEmailVerification(
    verification_id,
    { code: sentCode(verification_id)! },
  )
  return verified_token
}
