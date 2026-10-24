import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { getApiErrorStatus } from '@/api/error'

export function useProjectAction(projectId: string) {
  const queryClient = useQueryClient()
  const [message, setMessage] = useState('')
  const refresh = () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ['projects', projectId] }),
      queryClient.invalidateQueries({ queryKey: ['notifications'] }),
    ])
  const mutation = useMutation({
    mutationFn: (action: () => Promise<unknown>) => action(),
    onSuccess: refresh,
    onError: async (error) => {
      if (getApiErrorStatus(error) === 409) await refresh()
    },
  })
  const run = async (
    action: () => Promise<unknown>,
    success = '처리했어요.',
  ) => {
    setMessage('')
    const result = await mutation.mutateAsync(action)
    setMessage(success)
    return result
  }
  return {
    run,
    busy: mutation.isPending,
    message,
    error: mutation.error
      ? '요청을 처리하지 못했어요. 최신 상태와 권한을 확인해 주세요.'
      : '',
  }
}
