# Consumer Rebalance Lab

**Every consumer was healthy. Nothing was consuming.**

An interactive Kotlin/JVM simulator for the invisible cost of changing consumer
group membership. Replay one rolling deployment through four coordination
policies and watch a membership event become assignment churn, stop-the-world
time, lag, commit pressure, duplicate risk, state transfer, and recovery.

[telemetry.sh](https://telemetry.sh) is most useful when it connects a control
plane event to its workload consequence. This lab makes that join explicit.

## What it demonstrates

A consumer group can look healthy at the process level while its partitions
temporarily have no useful owner. Rebalance count alone does not describe the
blast radius: a full revocation, a six-partition transfer, and a prewarmed
handoff can each appear as “one rebalance.”

The model replays the same generation change through:

| Policy | Assignment behavior | Operational tradeoff |
| --- | --- | --- |
| Classic eager | Every member revokes every partition before reassignment | Simple, but creates a global work barrier and restores all state |
| Cooperative sticky | Unaffected members retain ownership while moved partitions transfer in rounds | Smaller lag wave, with rollout and multi-round coordination complexity |
| Static membership | A returning member reclaims its identity and assignment inside the timeout envelope | Avoids brief-restart churn; a missed session timeout still requires failure handling |
| Incremental warm handoff | Replacement capacity prewarms state before ownership changes incrementally | Lowest disruption here, at the cost of overlap capacity, bandwidth, and fencing |

The fourth policy is an operational pattern modeled on top of incremental
ownership transfer, not the name of a Kafka assignor.

With the default scenario—48 partitions, eight consumers, two rolling
restarts, 12,000 incoming records/second—the deterministic model produces:

| Policy | Revoked | Peak lag | Recovery | End-to-end p99 |
| --- | ---: | ---: | ---: | ---: |
| Classic eager | 48 | 72,000 | 35 s | 6,035 ms |
| Cooperative sticky | 12 | 6,000 | 7 s | 535 ms |
| Static membership | 0 | 6,000 | 7 s | 535 ms |
| Incremental warm handoff | 12 | 0 | 1 s | 35 ms |

These are analytical results, not measurements from a Kafka broker. Change the
traffic, capacity, restart time, timeout, state size, transfer bandwidth, and
seed in the UI to explore the model’s boundaries.

## Run it

The only build requirements are Java 11+ and the
[Kotlin command-line compiler](https://kotlinlang.org/docs/command-line.html).
The build is pinned and tested with Kotlin 2.4.10.

```sh
make check
make run
```

Then open <http://127.0.0.1:8080>.

Or use the container:

```sh
docker build -t consumer-rebalance-lab .
docker run --rm -p 8080:8080 consumer-rebalance-lab
```

The image downloads Kotlin 2.4.10 and verifies the compiler archive’s SHA-256
during the build. It runs as the unprivileged `10001:10001` user.

## API

The browser calls a dependency-free Kotlin HTTP endpoint:

```sh
curl 'http://127.0.0.1:8080/api/simulate?partitions=48&consumers=8&restarting_consumers=2'
```

Available positive integer query parameters:

- `partitions`
- `consumers`
- `restarting_consumers`
- `messages_per_second`
- `processing_per_consumer`
- `restart_seconds`
- `session_timeout_seconds`
- `state_per_partition_mib`
- `handoff_bandwidth_mbps`
- `deploy_second`
- `run_seconds`
- `seed`

`java -jar build/consumer-rebalance-lab.jar --json` prints the default
simulation without starting the server.

## Telemetry recipe

The assignment event becomes actionable when traces and metrics carry enough
context to join coordination to consequence:

```text
consumer.group.generation
consumer.rebalance.protocol
consumer.member.id
consumer.partitions.revoked
consumer.partitions.assigned
consumer.assignment.churn
consumer.rebalance.latency_ms
consumer.records_lag
consumer.state_transfer_bytes
consumer.commit.p99_ms
consumer.duplicate_risk
```

Useful investigation sequence:

1. Find the generation transition and identify the membership event that caused it.
2. Compare partitions revoked with partitions whose owner actually changed.
3. Overlay rebalance latency, assigned capacity, and records lag.
4. Separate coordination completion from backlog recovery.
5. Inspect state transfer and commit gaps at each ownership boundary.

That distinction matters: Kafka documents `EAGER` as revoking all owned
partitions before joining, while `COOPERATIVE` permits consumers to keep some
ownership during reassignment. Kafka’s newer consumer rebalance protocol is
fully incremental and removes the global synchronization barrier.

## Model boundaries

The simulator advances in one-second steps. Incoming records first add to group
lag; currently assigned consumers then remove records up to their aggregate
capacity. Each policy changes available capacity, assignment ownership, state
movement, and duplicate-risk estimates during the deployment window.

Intentional simplifications:

- partitions receive uniform traffic and state;
- processing capacity is constant per running consumer;
- broker, network, and storage contention are represented only through the
  configured state-transfer bandwidth;
- warm handoff assumes replacement capacity can restore before cutover;
- duplicate counts are comparative risk estimates, not delivery guarantees;
- static membership avoids reassignment only when restart time remains below
  the modeled session timeout.

Use the model to form and visualize hypotheses. Validate a production system
with client, broker, deployment, and workload telemetry.

## References

- [Kafka consumer rebalance protocol](https://kafka.apache.org/42/operations/consumer-rebalance-protocol/)
- [Kafka eager and cooperative protocol semantics](https://kafka.apache.org/42/javadoc/org/apache/kafka/clients/consumer/ConsumerPartitionAssignor.RebalanceProtocol.html)
- [Kafka cooperative sticky assignor](https://kafka.apache.org/42/javadoc/org/apache/kafka/clients/consumer/CooperativeStickyAssignor.html)
- [Kafka monitoring](https://kafka.apache.org/42/operations/monitoring/)
- [Kotlin command-line compiler](https://kotlinlang.org/docs/command-line.html)

## Stack

- Kotlin 2.4.10 / JVM for the model, JSON serialization, and HTTP server
- Java’s built-in `HttpServer`; no server framework or runtime dependencies
- semantic HTML, modern CSS, vanilla JavaScript, and Canvas 2D
- deterministic model and HTTP integration tests
- multi-stage non-root container and GitHub Actions CI

## License

[MIT](LICENSE)
