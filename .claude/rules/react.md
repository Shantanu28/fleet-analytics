---
paths: ["frontend/**"]
---

# React rules — frontend

**Work as a senior frontend engineer on this codebase.** The dashboard renders what the API says and never re-derives it. A value that is unavailable must look different from a zero — that distinction is the product. When a rule here and a spec disagree, the spec wins.

`docs/02-requirements.md` and `04-technical-spec.md` §5.2 remain authoritative. Snippets are illustrative, not components to scaffold.

## 1. Components and types

Functional components, strict TypeScript, explicit props. No `any`, no unchecked assertions; narrow `unknown` values crossing the boundary.

```tsx
type KpiCardProps = {
  readonly label: string
  readonly value: number | null      // null means unavailable, not zero
  readonly unavailableReason?: string
}

export function KpiCard({ label, value, unavailableReason }: KpiCardProps) { /* … */ }
```

## 2. State ownership

**TanStack Query owns server state. URL parameters own filters. Local state owns transient UI only.** Query keys carry identity, and the `AbortSignal` reaches `fetch` (`04` §5.2).

```tsx
const filters = useFiltersFromUrl()          // the URL is the source of truth
const { data, isPending, error, refetch } = useQuery({
  queryKey: ['dashboard', user.id, user.orgId, user.role, filters],
  queryFn: ({ signal }) => api.dashboard(filters, signal),
  retry: (failures, e) => !isAuthOrValidationError(e) && failures < 2,
})
```

Never copy query results into local state, and never recompute a backend metric client-side.

```tsx
const [rows, setRows] = useState([])                       // bad
useEffect(() => setRows(data?.rows ?? []), [data])         // bad: a second source of truth
const rows = data?.rows ?? []                              // good
```

Effects synchronise external systems. Do not use one for a value derivable during render.

## 3. Rendering states

Loading, error, empty and unavailable are four distinct renders.

```tsx
if (isPending) return <SectionSkeleton />                    // never the previous filter's values
if (error) return <ErrorPanel onRetry={refetch} />
if (value === null) return <Unavailable reason={unavailableReason} />
return <Value amount={value} />                              // 0 renders as a real 0
```

## 4. Accessibility and structure

Semantic HTML, labelled controls, visible keyboard focus, stable list keys, and a text or table alternative for every chart. Prefer cohesive components and composition — no arbitrary line limits, and no `useMemo`/`useCallback` sprinkled pre-emptively.

```tsx
<label htmlFor="team-filter">Team</label>
<select id="team-filter" value={filters.teamId ?? ''} onChange={onTeamChange}>…</select>
```

## 5. HTTP client

Centralise error parsing in the typed client. Check non-2xx explicitly and survive a malformed or non-JSON body. Never render a raw response body or leak credentials into diagnostics.

```ts
if (!response.ok) {
  const problem = await response.json().catch(() => null)   // may not be JSON at all
  throw new ApiError(response.status, problem?.type ?? 'about:blank')
}
```

## 6. Testing

Test user-visible behaviour at the HTTP boundary — including delayed responses, logout and account changes. **Isolated `QueryClient` per test.** Do not test library internals.

```tsx
const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
render(<QueryClientProvider client={client}><Dashboard /></QueryClientProvider>)
expect(await screen.findByText(/no merged PRs in this period/i)).toBeVisible()
```

Report commands actually run; never claim an unexecuted test passed.
