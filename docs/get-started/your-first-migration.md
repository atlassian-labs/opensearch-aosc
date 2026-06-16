# Your First Migration

This tutorial runs a small local migration with continuous writes. It changes a field mapping and shard count by moving from `events-v1` to a pre-created `events-v2` index.

Run it from a clean local Docker cluster. The final `dockerDown` step removes the Docker volumes; if a previous run was interrupted and `events-v1` already exists, run that cleanup command before starting again.

::: tip What this proves
This tutorial keeps writes running while AOSC backfills and replays into a target index with a different mapping and shard count. The writer retries rejected writes so the cutover behavior is visible without losing documents.
:::

## Prerequisites

- JDK 17 is known working; the plugin source targets Java 11 bytecode.
- Docker and Docker Compose.
- `curl` and `jq`.

## 1. Build the Plugin

From the repository root:

```bash
./gradlew :aosc-plugin:assemble -Dopensearch.version=2.19.0
```

The ZIP is created under `aosc-plugin/build/distributions/`.

## 2. Start the Local Cluster

Use the Gradle Docker tasks from the repository root:

```bash
export OPENSEARCH_INITIAL_ADMIN_PASSWORD=Admin@123
./gradlew :aosc-plugin:dockerUp -Dopensearch.version=2.19.0
```

Wait for the cluster to become healthy:

```bash
until curl -s http://localhost:9200/_cluster/health | jq -e '.status == "green" or .status == "yellow"' >/dev/null; do
  sleep 2
done
```

## 3. Create the Source Index and Alias

```bash
curl -s -X PUT http://localhost:9200/events-v1 \
  -H 'Content-Type: application/json' \
  -d '{
  "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
  "mappings": {
    "properties": {
      "timestamp": { "type": "date" },
      "message":   { "type": "text" },
      "status":    { "type": "text" }
    }
  }
}'

curl -s -X POST http://localhost:9200/_aliases \
  -H 'Content-Type: application/json' \
  -d '{
  "actions": [
    { "add": { "index": "events-v1", "alias": "events", "is_write_index": true } }
  ]
}'
```

Applications should use `events`, not `events-v1`, so AOSC can swap the alias later.

## 4. Start Continuous Writes

In a second terminal:

```bash
i=1
while true; do
  payload=$(printf '{"timestamp":"%s","message":"Event %s","status":"ok"}' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$i")
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://localhost:9200/events/_doc/$i" \
    -H 'Content-Type: application/json' \
    -d "$payload")
  if [ "$code" = "201" ] || [ "$code" = "200" ]; then
    echo "Wrote doc $i"
    i=$((i + 1))
  else
    echo "Rejected doc $i (HTTP $code); retrying"
    sleep 0.5
    continue
  fi
  sleep 0.1
done
```

Leave this running. During cutover you may see rejected writes while the source write block is active; a few `HTTP 403` retries are normal in this tutorial. Real applications must retry those writes.

::: info Two-terminal workflow
Keep the writer loop running in one terminal and run migration commands from another. That makes the cutover retry behavior easy to see.
:::

## 5. Create the Target Index

Create the target with the desired mapping and shard count:

```bash
curl -s -X PUT http://localhost:9200/events-v2 \
  -H 'Content-Type: application/json' \
  -d '{
  "settings": { "number_of_shards": 2, "number_of_replicas": 0 },
  "mappings": {
    "properties": {
      "timestamp": { "type": "date" },
      "message":   { "type": "text" },
      "status":    { "type": "keyword" }
    }
  }
}'
```

## 6. Start the Migration

```bash
curl -s -X POST http://localhost:9200/_plugins/_aosc/events-v1/_start \
  -H 'Content-Type: application/json' \
  -d '{
  "target_index": "events-v2",
  "alias": "events"
}' | jq '.'
```

Expected response shape:

```json
{
  "migration_id": "...",
  "accepted": true
}
```

## 7. Monitor Progress

```bash
while true; do
  resp=$(curl -s http://localhost:9200/_plugins/_aosc/events-v1/_status)
  phase=$(echo "$resp" | jq -r '.phase')
  echo "Phase: $phase"
  [ "$phase" = "COMPLETED" ] && break
  [ "$phase" = "FAILED" ] && { echo "$resp" | jq '.error_message'; break; }
  sleep 2
done
```

A successful migration normally follows:

```text
INITIALIZING -> ACTIVE -> PREPARING_TARGET -> CUTTING_OVER -> CATCHING_UP -> COMPLETING -> COMPLETED
```

During cutover, AOSC briefly blocks source writes, catches up the last operations, and swaps the alias. By default, the old source index stays write-blocked after success so it cannot silently accept writes after the alias has moved. To clear the block automatically, set `"options": {"remove_source_write_block_on_success": true}` on the request or set `aosc.defaults.remove_source_write_block_on_success` to `true`.

::: details What to expect in `_status`
For this small local run, phases can move quickly. If the status loop jumps from `ACTIVE` to `COMPLETED`, that usually means the backfill, replay, final catch-up, and alias swap finished between two polling intervals.
:::

## 8. Verify and Clean Up

Stop the writer with `Ctrl+C`, then inspect the alias, mapping, shard count, and document count:

```bash
curl -s http://localhost:9200/_alias/events | jq '.'
curl -s http://localhost:9200/events-v2/_mapping | jq '."events-v2".mappings.properties.status'
curl -s http://localhost:9200/events-v2/_settings | jq '."events-v2".settings.index.number_of_shards'
curl -s http://localhost:9200/events-v2/_count | jq '.count'
```

The document count should match the last writer ID. The writer retries rejected documents, so even writes that hit the brief write block during cutover are eventually indexed after the block lifts (they now land on the target via the alias).

::: info Source index stays write-blocked
After success, the old source index (`events-v1`) is write-blocked by default. The alias `events` points at the target, so application writes are unaffected. Remove the block manually before deleting or reusing the old index, or start the migration with `remove_source_write_block_on_success: true`.
:::

Shut down the local cluster:

```bash
./gradlew :aosc-plugin:dockerDown -Dopensearch.version=2.19.0
```
