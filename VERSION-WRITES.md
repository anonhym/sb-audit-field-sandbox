# Which write paths maintain `@Version`?

Branched off `approach/custom-isnew`. Goal of the original request: make sure *every* update path
keeps `@Version` correct, "no matter the dev" — initially via a Spring AOP aspect that injects the
increment.

**Finding (measured live, Spring Data MongoDB 5.1 / Spring Boot 4.1):** that aspect is mostly
unnecessary, because Spring Data already auto-increments `@Version` on its template update methods.
The premise was true of *older* Spring Data; it isn't anymore.

## Native behaviour (no aspect)

| Write path | `@Version` maintained natively? |
|------------|---------------------------------|
| `save()` insert | ✅ sets `version = 0` |
| `save()` update | ✅ increments (+ optimistic-lock check) |
| `updateFirst` | ✅ increments |
| `updateMulti` | ✅ increments |
| `upsert` (update) | ✅ increments |
| `upsert` (insert) | ✅ new doc gets a version |
| `findAndModify` | ✅ increments |
| pipeline `AggregationUpdate` | ✅ increments |
| **`bulkOps`** | ❌ **not incremented** — field left untouched |
| **`findAndReplace`** | ❌ **removes the version field** unless the replacement carries it |

So the only real gaps are **`bulkOps`** and **`findAndReplace`**. Note also that none of the template
update methods add optimistic-lock *checking* (they update whatever matches the query); they only
keep the version value *maintained* so that `save()`-based locking stays correct.

## What this branch keeps

`config/VersionGuardAspect` — a small AOP aspect (needs `aspectjweaver`; `spring-boot-starter-aop`
was removed in Boot 4) that logs a WARN when `bulkOps` or `findAndReplace` is called for a versioned
entity, pointing the developer at the fix:

- `bulkOps` → add `.inc("version", 1)` to each bulk update.
- `findAndReplace` → prefer `save()`/`findAndModify`, or set the current version on the replacement.

It does **not** try to inject the increment itself (Spring Data covers the methods where that would
matter, and the two it doesn't can't be safely rewritten from an aspect). Policy: warn and allow.

## How to verify

```
./mvnw spring-boot:run
POST /api/products {...}                       -> version 0
POST /api/experiments/{id}/inc-stock           -> version 1   (updateFirst, native)
POST /api/updates/{id}/find-and-modify         -> version 2   (findAndModify, native)
POST /api/updates/bulk?category=electronics    -> WARN: bulkOps not incremented
PUT  /api/updates/{id}/find-and-replace {...}  -> WARN: findAndReplace drops version
```
