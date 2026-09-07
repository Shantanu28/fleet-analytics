/**
 * Browser history as an external store.
 *
 * The applied selection is not React state — it lives in the URL, which the user can change with
 * the back button at any moment. Mirroring it into `useState` would create a second source of truth
 * that drifts the first time history moves without a React event. `useSyncExternalStore` subscribes
 * to the real thing instead, so back and forward need no bookkeeping to work (AC-02.4).
 */
import { useCallback, useSyncExternalStore } from 'react'
import { readSelection, toSearchString, type Selection } from './selection'

/** History changes made by this app do not fire `popstate`, so they announce themselves. */
const SELECTION_EVENT = 'fleet:selection-changed'

function subscribe(onChange: () => void): () => void {
  window.addEventListener('popstate', onChange)
  window.addEventListener(SELECTION_EVENT, onChange)
  return () => {
    window.removeEventListener('popstate', onChange)
    window.removeEventListener(SELECTION_EVENT, onChange)
  }
}

// A string snapshot, so React's identity check settles immediately instead of re-rendering forever.
function currentSearch(): string {
  return window.location.search
}

function serverSearch(): string {
  return ''
}

function announce(): void {
  window.dispatchEvent(new Event(SELECTION_EVENT))
}

export type SelectionNavigation = {
  readonly selection: Selection
  /** An applied user change: one history entry, so one Back returns to the previous view. */
  readonly push: (next: Selection) => void
  /** Canonicalisation and corrections: replaces the entry, so Back never lands on a redirect loop. */
  readonly replace: (next: Selection) => void
}

export function useDashboardSelection(): SelectionNavigation {
  const search = useSyncExternalStore(subscribe, currentSearch, serverSearch)

  const push = useCallback((next: Selection) => {
    const target = `${window.location.pathname}${toSearchString(next)}`
    // Pushing an identical entry would make Back appear broken: it would return to the same view.
    if (target === `${window.location.pathname}${window.location.search}`) return
    window.history.pushState(null, '', target)
    announce()
  }, [])

  const replace = useCallback((next: Selection) => {
    const target = `${window.location.pathname}${toSearchString(next)}`
    if (target === `${window.location.pathname}${window.location.search}`) return
    window.history.replaceState(null, '', target)
    announce()
  }, [])

  return { selection: readSelection(search), push, replace }
}
