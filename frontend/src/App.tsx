import { useState } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DashboardPage } from './dashboard/DashboardPage'
import { LoginView } from './LoginView'
import { SessionProvider, useSession } from './auth/session'

function Routes() {
  const { session } = useSession()
  return session ? <DashboardPage /> : <LoginView />
}

/**
 * Background refetching is off: this dashboard's data changes only when the dataset does
 * (04 §5.2). Authentication and validation failures are not retried — they surface to the user.
 */
function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
        refetchOnMount: false,
        staleTime: Infinity,
      },
    },
  })
}

export function App({ client }: { client?: QueryClient } = {}) {
  // Created once. Building it during render would discard every cache on any rerender.
  const [fallbackClient] = useState(createQueryClient)
  const queryClient = client ?? fallbackClient

  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>
        <Routes />
      </SessionProvider>
    </QueryClientProvider>
  )
}
