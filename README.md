# sb-version-sandbox

A Spring Boot **4.1** + **Spring Data MongoDB** playground for experimenting with MongoDB
operations through both **`MongoRepository`** and **`MongoTemplate`**, with a focus on
**`@Version` optimistic locking** and the **migration paths** you hit when you add a version
field to a collection that already has data.

- **Java:** 21 (compiles/runs fine on a newer JDK such as 25)
- **Build:** Maven (wrapper included — `./mvnw`)
- **Mongo:** auto-started via Spring Boot Docker Compose support (needs Docker running)

## Running

The recommended way (Docker Compose support auto-starts/stops Mongo):

```bash
./mvnw spring-boot:run
```

On startup this:
1. brings up a `mongo:8` container from [`compose.yaml`](compose.yaml),
2. wires `spring.data.mongodb` to it automatically,
3. seeds a few products (toggle with `app.seed.enabled=false`).

> **Note:** Docker Compose support is a dev-time feature and is **excluded from the packaged
> fat jar** by the Spring Boot Maven plugin. If you run `java -jar target/*.jar`, point it at a
> Mongo instance yourself, e.g. `SPRING_DATA_MONGODB_URI=mongodb://localhost:27017/sandbox`.

Then drive the experiments with [`requests.http`](requests.http) (IntelliJ HTTP client or the
VS Code *REST Client* extension), or `curl`.

## The `@Version` story

`Product` carries a `@Version Long version`. Because it's a **wrapper** (`Long`, not `long`),
Spring Data uses one simple rule:

| stored `version` | loaded value | `repository.save()` routes to | result |
|------------------|--------------|-------------------------------|--------|
| `0`, `1`, `2`, … | non-null     | **update** (with version check) | version increments; stale version → `OptimisticLockingFailureException` |
| *field absent*   | `null`       | **insert**                    | `_id` already exists → **duplicate-key error** |

That second row is the trap when you add `@Version` to a model whose collection already holds
documents written before the field existed. The migration is to **back-fill the field to `0`**
(exactly what a native Spring Data insert stores), after which those documents load with a
non-null version and update normally.

## Endpoints

### CRUD — `MongoRepository` (`/api/products`)
| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/api/products` | create (returns `version: 0`) |
| `GET`  | `/api/products` | list all, or `?category=` |
| `GET`  | `/api/products/{id}` | fetch one |
| `GET`  | `/api/products/{id}/raw` | the raw stored BSON (shows whether `version` exists) |
| `PUT`  | `/api/products/{id}` | load-modify-save (increments version) |
| `DELETE` | `/api/products/{id}` | delete |
| `GET`  | `/api/products/search` | `?category=&minPrice=` via `MongoTemplate` `Criteria` |

### Experiments (`/api/experiments`)
| Method | Path | What it demonstrates |
|--------|------|----------------------|
| `POST` | `/legacy-doc` | inserts a document with **no version field** (raw BSON) |
| `POST` | `/{id}/load-then-save` | OK for versioned docs; **duplicate-key** for legacy docs |
| `POST` | `/{id}/concurrent-update` | two writers race → second hits **optimistic-lock failure** |
| `POST` | `/{id}/inc-stock` | `$inc` via `MongoTemplate` (bypasses version check) |
| `POST` | `/raise-prices` | `$mul` over a whole category (`updateMulti`) |
| `GET`  | `/count-by-category` | aggregation pipeline |
| `POST` | `/reset` | drops the collection |

### Migration (`/api/migrations`)
| Method | Path | What it does |
|--------|------|--------------|
| `GET`  | `/version-stats` | counts docs missing the version field + distribution by version |
| `POST` | `/backfill-version` | sets `version = 0` where the field is missing (`updateMany`) |

## A full migration walkthrough

```bash
# 1. Create a legacy doc with no version field — copy the returned id
curl -X POST 'http://localhost:8080/api/experiments/legacy-doc?name=Legacy%20Widget'

# 2. Confirm it has no version field
curl 'http://localhost:8080/api/products/<ID>/raw'

# 3. Try to save it -> fails with a duplicate-key error (null version => treated as new => insert)
curl -X POST 'http://localhost:8080/api/experiments/<ID>/load-then-save'

# 4. See how many docs need migrating
curl 'http://localhost:8080/api/migrations/version-stats'

# 5. Back-fill version=0 on the legacy docs
curl -X POST 'http://localhost:8080/api/migrations/backfill-version'

# 6. Save again -> now succeeds, version -> 1
curl -X POST 'http://localhost:8080/api/experiments/<ID>/load-then-save'
```

## Project layout

```
src/main/java/com/example/versionsandbox/
├─ domain/Product.java              # @Version Long, the aggregate
├─ repository/ProductRepository.java# MongoRepository + derived/@Query methods
├─ service/
│  ├─ ProductService.java           # repository-based CRUD
│  ├─ MongoTemplateService.java     # Criteria/update/aggregate + legacy-insert + back-fill
│  └─ ExperimentService.java        # scripted @Version scenarios
├─ web/                             # REST controllers + exception handler
└─ seed/DataSeeder.java             # startup seed (CommandLineRunner)
```

## Config knobs (`application.yml`)

- `app.seed.enabled` — seed sample data on startup (default `true`).
- `spring.data.mongodb.database` — database name (default `sandbox`).
- `spring.docker.compose.*` — Docker Compose lifecycle.
- `MongoTemplate` logging is at `DEBUG`, so the actual Mongo commands show in the console.
