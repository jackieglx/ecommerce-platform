# Redis (local dev)

- Image: `redis:7-alpine`
- Default connection: `redis:6379` inside the compose network, `localhost:6379` from host.
- Eviction policy is default (`noeviction`) in this minimal setup; configure per-service if needed.
- Sample env (see `infra/local/env.example`):
  - `REDIS_HOST=redis`
  - `REDIS_PORT=6379`

