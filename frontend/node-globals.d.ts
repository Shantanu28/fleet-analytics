/**
 * The one Node global the build and browser-suite configuration reads.
 *
 * Declared here rather than by adding `@types/node`, which is not among the dependencies
 * `docs/04-technical-spec.md` §1 approves. Only `process.env` is declared, and only as readonly
 * strings, so nothing else from Node can creep into a type-checked file behind it.
 *
 * Application code under `src/` must not use this: `process` does not exist in the browser, and a
 * bundled `process.env` read throws at runtime. It exists for `vite.config.ts` and
 * `playwright.config.ts`, which run in Node.
 */
declare const process: {
  readonly env: Readonly<Record<string, string | undefined>>
}
