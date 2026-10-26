import { useQuery } from '@tanstack/react-query'
import { authApi } from '@/api/services'

export function useSession() {
  return useQuery({
    queryKey: ['account'],
    queryFn: () => authApi.me(),
    retry: false,
  })
}
