# sb-version-sandbox

A Spring Boot **4.1** + **Spring Data MongoDB** sandbox for one specific production problem:

> **How do you add `@Version` optimistic locking to a MongoDB collection that already has
> documents — _without_ back-filling the version field first (a business restriction)?**

Naively adding `@Version` breaks: an existing document has no `version` field, so it loads as
`version = null`, Spring Data decides the entity "is new", routes `save()` to an **insert**, and the
existing `_id` blows up with a duplicate-key error. The usual fix — a bulk back-fill that sets
`version = 0` everywhere — is **not allowed here**. So each strategy has to cope with version-less
documents at read/write time instead.

- **Java:** 21 (compiles/runs on a newer JDK such as 25)
- **Build:** Maven (wrapper included — `./mvnw`)
- **Mongo:** auto-started via Spring Boot Docker Compose (needs Docker running)

## How the repo is organised

| Branch | What it is |
|--------|------------|
| **`main`** | **Baseline.** `Product` has **no** `@Version`; documents are stored with no version field. This is "production today". Plus a shared test harness every branch reuses. |
| `approach/naive` | Add `@Version` and nothing else — the **failure control** (legacy docs → duplicate-key). |
| `approach/lazy-on-read` | `AfterConvertCallback` defaults `version = 0` when a doc loads without it. |
| `approach/explicit-upsert` | Custom save via `replaceOne(_id, upsert)` — never mis-routes a null version to an insert. |
| `approach/custom-isnew` | `Product implements Persistable` — an existing `_id` with null version is treated as *not new* → update. |
| `approach/version-with-backfill` | Reference only: the standard `@Version` + bulk back-fill approach you're **not** allowed to use. |

Each `approach/*` branch changes **only** the versioning strategy (the entity + one small mechanism).
The harness — controllers, services, seeder, inspection — is identical across branches, because it
reads the version from the **stored BSON**, never from a `version` property on `Product`. See
[`APPROACHES.md`](APPROACHES.md) for the experiment matrix and findings.

## Running

```bash
./mvnw spring-boot:run        # Docker must be running; mongo:8 auto-starts
```

> Docker Compose support is dev-time only and **excluded from the packaged fat jar**. If you run
> `java -jar target/*.jar`, point it at Mongo yourself, e.g.
> `SPRING_DATA_MONGODB_URI=mongodb://localhost:27017/sandbox`.

Drive experiments with [`requests.http`](requests.http) (IntelliJ HTTP client / VS Code *REST
Client*) or `curl`.

## The repeatable experiment (run on each branch)

```bash
# 1. Insert a version-less "legacy" document (simulates existing prod data). Copy the id.
curl -X POST 'http://localhost:8080/api/experiments/legacy-doc?name=Legacy%20Widget'

# 2. Confirm it has no version field
curl 'http://localhost:8080/api/products/<ID>/raw'

# 3. Load it and save it back — THIS is where branches differ
curl -X POST 'http://localhost:8080/api/experiments/<ID>/load-then-save'

# 4. Race two writers on it
curl -X POST 'http://localhost:8080/api/experiments/<ID>/concurrent-update'

# 5. Inspect how many docs still lack a version field
curl 'http://localhost:8080/api/status/version-stats'
```

On `main` step 3 just succeeds (last-write-wins, no version written) and step 4 shows both writers
winning in turn — there's no protection yet. That's the motivation; the branches add it.

## Endpoints

### CRUD — `MongoRepository` (`/api/products`)
| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/api/products` | create |
| `GET`  | `/api/products` | list all, or `?category=` |
| `GET`  | `/api/products/{id}` | fetch one |
| `GET`  | `/api/products/{id}/raw` | raw stored BSON (shows whether `version` exists) |
| `PUT`  | `/api/products/{id}` | load-modify-save |
| `DELETE` | `/api/products/{id}` | delete |
| `GET`  | `/api/products/search` | `?category=&minPrice=` via `MongoTemplate` `Criteria` |

### Experiments (`/api/experiments`)
| Method | Path | What it does |
|--------|------|--------------|
| `POST` | `/legacy-doc` | insert a document with **no** version field (raw BSON) |
| `POST` | `/{id}/load-then-save` | load then save — the key per-branch probe |
| `POST` | `/{id}/concurrent-update` | two writers race on one document |
| `POST` | `/{id}/inc-stock` | `$inc` via `MongoTemplate` |
| `POST` | `/raise-prices` | `$mul` over a category (`updateMulti`) |
| `POST` | `/reset` | drop the collection |

### Status / inspection (`/api/status`)
| Method | Path | What it does |
|--------|------|--------------|
| `GET`  | `/version-stats` | total docs, how many lack `version`, distribution by version value |
| `GET`  | `/count-by-category` | aggregation pipeline |

### Update surface — `MongoTemplate` (`/api/updates`)
Exercises the rest of the update API beyond `updateFirst`/`updateMulti`:
| Method | Path | Operation |
|--------|------|-----------|
| `POST` | `/upsert` | `upsert` with `$setOnInsert` |
| `POST` | `/{id}/find-and-modify` | `findAndModify` (atomic `$inc`, returns new doc) |
| `PUT`  | `/{id}/find-and-replace` | `findAndReplace` (swap whole doc) |
| `POST` | `/bulk` | `bulkOps` (batched `updateMulti` + `upsert`) |
| `POST` | `/compute-inventory-value` | pipeline `AggregationUpdate` (`inventoryValue = price*stock`) |
| `POST` | `/{id}/tags/add` · `/push` · `/pull` | `$addToSet` · `$push` · `$pull` (schemaless `tags` array) |
| `POST` | `/{id}/min-price` · `/touch` · `/unset` | `$min` · `$currentDate` · `$unset` |

## Project layout

```
src/main/java/com/example/versionsandbox/
├─ domain/Product.java              # baseline: NO version field
├─ repository/ProductRepository.java# MongoRepository + derived queries
├─ service/
│  ├─ ProductService.java           # repository-based CRUD
│  ├─ MongoTemplateService.java     # Criteria/update/aggregate + raw legacy-insert + storedVersion()
│  └─ ExperimentService.java        # version-agnostic probes (reads version from raw BSON)
├─ web/                             # ProductController, ExperimentController, StatusController, ApiExceptionHandler
└─ seed/DataSeeder.java             # seeds version-less docs on startup
```

## Config knobs (`application.yml`)

- `app.seed.enabled` — seed sample data on startup (default `true`).
- `spring.data.mongodb.database` — database name (default `sandbox`).
- `spring.docker.compose.*` — Docker Compose lifecycle.
- `MongoTemplate` logging is at `DEBUG`, so the actual Mongo commands show in the console.
