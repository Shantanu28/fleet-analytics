---
paths: ["backend/**"]
---

# Java rules — backend

**Work as a senior backend engineer on this codebase.** The bar is code a reviewer can check against the specs without running it: invariants enforced where the object is built, one obvious place for each concern, and no cleverness that hides a contract rule. When a rule here and a spec disagree, the spec wins — say so rather than quietly choosing.

`docs/01-metrics-contract.md`, `04-technical-spec.md` and `05-testing-spec.md` remain authoritative for behaviour. These are coding conventions. Snippets below are illustrative, not classes to scaffold.

## 1. Types and construction

Prefer **records** for DTOs, aggregate inputs and value objects. Validate in the compact constructor; defensively copy mutable collections.

```java
public record DateRange(OffsetDateTime startInclusive, OffsetDateTime endExclusive) {
    public DateRange {
        Objects.requireNonNull(startInclusive, "startInclusive");
        Objects.requireNonNull(endExclusive, "endExclusive");
        if (!endExclusive.isAfter(startInclusive)) {
            throw new IllegalArgumentException("endExclusive must follow startInclusive");
        }
    }
}

public record ComparisonRow(String scopeId, List<String> notes) {
    public ComparisonRow {
        notes = List.copyOf(notes);   // caller cannot mutate ours afterwards
    }
}
```

Prefer record-style accessors (`value()`) over public setters on application-owned types. **Generated jOOQ types and framework conventions are exceptions — never rewrite generated code for style.**

```java
record MergedPrCount(long value) {}          // good: count.value()
class MergedPrCount { void setValue(long v); }  // avoid on our own value types
organisationRecord.setName("Fleet");            // fine — generated, leave it alone
```

Use an immutable class with a private constructor and a named factory or builder **only when construction genuinely warrants it** — several optional inputs, not everywhere. Every path enforces the same invariants. Do **not** impose private constructors on public records, Spring services or exception types.

```java
public final class DashboardQuery {
    private final DateRange range;
    private final UUID teamId;      // optional
    private DashboardQuery(Builder b) {
        this.range = Objects.requireNonNull(b.range);   // one validation site
        this.teamId = b.teamId;
    }
    public static Builder forRange(DateRange range) { return new Builder(range); }
    // Builder omitted — build() delegates to this constructor, so nothing bypasses validation
}
```

Exhaustive `switch` expressions over fixed enums or sealed types are welcome — no `default`, so the compiler catches a new case. Avoid duplicated branching, but do not build a strategy hierarchy to remove a small, clear switch.

```java
String label = switch (terminalState) {
    case MERGED          -> "Merged";
    case CLOSED_UNMERGED -> "Closed unmerged";
    case OPEN            -> "Open";
};
```

Avoid speculative interfaces, generic repositories and unnecessary inheritance. **Do not add Lombok or any dependency to satisfy these conventions.**

Shared value and context types live in **their own files**, not nested inside the query or controller class that happens to return them first — a record declared inside a repository becomes that repository's name in every signature that uses it.

## 1a. Imports

**Import types normally, annotations included.** A fully qualified name inline is for a real collision or generated code, not for saving an import line.

```java
import org.springframework.stereotype.Repository;

@Repository                                        // good
public class ContextQueries { … }

@org.springframework.stereotype.Repository         // avoid: nothing collides here
public class ContextQueries { … }

// A static import of a member does not collide with a type of the same name:
import static com.fleet.analytics.data.jooq.tables.Repository.REPOSITORY;   // the field
import org.springframework.stereotype.Repository;                          // the annotation
```

## 2. Layering

Constructor injection, `final` fields. Controllers stay thin. **SQL population selection lives in the data layer; metric derivation lives in the pure metrics layer** (`04` §4). Generated persistence types never cross into API responses or pure metric interfaces.

```java
@Service
public final class MergeRateCalculator {              // pure: no HTTP, no SQL
    private final PullRequestCounts counts;           // data-layer port
    public MergeRateCalculator(PullRequestCounts counts) { this.counts = counts; }
}

// Bad: a generated jOOQ record leaking out of the web layer
@GetMapping("/orgs") OrganisationRecord get() { ... }
```

## 3. Contract obligations

Money in **integer cents**, never floating point. UTC timestamps. Null and unavailable states exactly as the contract defines them — **never a convenient zero**. Thresholds compared exactly. Tenant scope from the verified principal, applied before aggregation.

```java
// PostgreSQL sum(bigint) returns numeric -> BigDecimal, not long
BigDecimal spendCents = row.get(SPEND_CENTS, BigDecimal.class);

// good: an absent denominator stays absent
return mergedPrs == 0
        ? MetricValue.noDenominator("no merged PRs in this period")
        : MetricValue.of(spendCents.divide(BigDecimal.valueOf(mergedPrs), MathContext.DECIMAL64));

// bad: double arithmetic on money, and a zero that means "we don't know"
double perPr = mergedPrs == 0 ? 0.0 : spendCents.doubleValue() / mergedPrs;
```

## 4. Exceptions and error handling

Specific exceptions live **beside the behaviour they describe**; HTTP mapping lives in `web/error`; security-filter failures are handled in the security layer. Do not invent a shared base exception until something consumes it.

```java
public final class InvalidAnalyticsFilterException extends RuntimeException {
    public InvalidAnalyticsFilterException() {
        super("The requested filter is not available.");
    }
}
```

Throw it when a requested filter is not valid for the authenticated organisation. **Unknown and foreign-organisation identifiers produce the same public response** — never include the identifier or any ownership detail. Map it centrally; status and `ProblemDetail` belong to the web adapter, not the exception.

```java
@RestControllerAdvice
class ApiErrorHandler {
    @ExceptionHandler(InvalidAnalyticsFilterException.class)
    ProblemDetail onInvalidFilter(InvalidAnalyticsFilterException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setType(URI.create("urn:fleet:problem:unknown-filter"));   // stable type from the API contract
        return problem;
    }
}
```

Also:

- Reuse Spring's handling for malformed requests and validation errors; no custom exception per framework failure.
- Distinguish invalid client input from internal invariant violations — **not every `IllegalArgumentException` is a 400**.
- `AuthenticationEntryPoint` and `AccessDeniedHandler` handle filter-chain 401/403; controller advice never sees them.
- Preserve framework-required status codes and headers.
- **Expected analytics outcomes are results, not exceptions**: zero denominator, missing baseline, insufficient sample, unavailable source.
- An unexpected failure returns a sanitised **500** — never a fake success with zero metrics.
- Never expose exception messages, SQL, stack traces, tokens, passwords or restricted domains in API errors.
- Preserve causes when translating (`throw new X("...", cause)`). Do not catch merely to rethrow, or log the same failure twice.
- **Add a custom exception only when the milestone implements its behaviour.** This file does not authorise scaffolding them.

Test status, content type, stable problem type, sanitised content, and cross-tenant indistinguishability.

## 5. Testing

Focused tests, deterministic fixtures, a regression test for every bug fixed. Report commands actually run; **never claim an unexecuted test passed.**
