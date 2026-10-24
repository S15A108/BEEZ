import { Link, useNavigate, useSearchParams } from 'react-router'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { membersApi } from '@/api/services'
import { useSession } from '@/features/auth/useSession'
import { AsyncState } from '@/components/AsyncState'
import { Button } from '@/components/ui/button'
import { formatDate } from '@/lib/formatDate'
import { getApiErrorMessage } from '@/api/error'

export default function InvitationPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? params.get('invite') ?? ''
  const session = useSession()
  const navigate = useNavigate()
  const client = useQueryClient()
  const invitation = useQuery({
    queryKey: ['invitation', token],
    queryFn: () => membersApi.previewInvitation({ token }),
    retry: false,
  })
  const accept = useMutation({
    mutationFn: () => membersApi.acceptInvitation({ token }),
    onSuccess: async (data) => {
      await client.invalidateQueries({ queryKey: ['projects'] })
      navigate(`/projects/${data.project.id}`)
    },
  })
  return (
    <main className="mx-auto mt-16 max-w-lg rounded-3xl border bg-card p-8">
      <h2 className="mb-6 text-2xl font-bold">프로젝트 초대</h2>
      <AsyncState query={invitation}>
        {(data) => (
          <div className="space-y-5">
            <p className="text-lg font-semibold">
              {data.project_name}에서 함께해요
            </p>
            <p className="text-sm text-muted-foreground">
              초대 만료 {formatDate(data.expires_at)}
            </p>
            {session.data ? (
              <Button
                disabled={accept.isPending}
                onClick={() => accept.mutate()}
              >
                초대 수락하고 참여하기
              </Button>
            ) : (
              <Button asChild>
                <Link to={`/login?invite=${encodeURIComponent(token)}`}>
                  로그인하고 초대 수락하기
                </Link>
              </Button>
            )}
            {accept.error && (
              <p role="alert" className="text-sm text-destructive">
                {getApiErrorMessage(accept.error)}
              </p>
            )}
          </div>
        )}
      </AsyncState>
    </main>
  )
}
