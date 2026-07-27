package lab

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round

data class Config(
    val partitions: Int = 48,
    val consumers: Int = 8,
    val restartingConsumers: Int = 2,
    val messagesPerSecond: Int = 12_000,
    val processingPerConsumer: Int = 1_800,
    val restartSeconds: Int = 4,
    val sessionTimeoutSeconds: Int = 10,
    val statePerPartitionMiB: Int = 4,
    val handoffBandwidthMbps: Int = 400,
    val deploySecond: Int = 15,
    val runSeconds: Int = 75,
    val seed: Int = 31_173,
) {
    fun normalized(): Config {
        val normalizedConsumers = consumers.coerceIn(2, 64)
        val normalizedRunSeconds = runSeconds.coerceIn(30, 180)
        return copy(
            partitions = partitions.coerceIn(4, 512),
            consumers = normalizedConsumers,
            restartingConsumers = restartingConsumers.coerceIn(1, normalizedConsumers),
            messagesPerSecond = messagesPerSecond.coerceIn(100, 200_000),
            processingPerConsumer = processingPerConsumer.coerceIn(100, 50_000),
            restartSeconds = restartSeconds.coerceIn(1, 60),
            sessionTimeoutSeconds = sessionTimeoutSeconds.coerceIn(2, 120),
            statePerPartitionMiB = statePerPartitionMiB.coerceIn(1, 256),
            handoffBandwidthMbps = handoffBandwidthMbps.coerceIn(10, 10_000),
            deploySecond = deploySecond.coerceIn(2, normalizedRunSeconds - 10),
            runSeconds = normalizedRunSeconds,
            seed = seed.coerceIn(1, Int.MAX_VALUE),
        )
    }

    fun toJson(): String = jsonObject(
        "partitions" to partitions,
        "consumers" to consumers,
        "restartingConsumers" to restartingConsumers,
        "messagesPerSecond" to messagesPerSecond,
        "processingPerConsumer" to processingPerConsumer,
        "restartSeconds" to restartSeconds,
        "sessionTimeoutSeconds" to sessionTimeoutSeconds,
        "statePerPartitionMiB" to statePerPartitionMiB,
        "handoffBandwidthMbps" to handoffBandwidthMbps,
        "deploySecond" to deploySecond,
        "runSeconds" to runSeconds,
        "seed" to seed,
    )

    companion object {
        private val queryKeys = mapOf(
            "partitions" to "partitions",
            "consumers" to "consumers",
            "restarting_consumers" to "restartingConsumers",
            "messages_per_second" to "messagesPerSecond",
            "processing_per_consumer" to "processingPerConsumer",
            "restart_seconds" to "restartSeconds",
            "session_timeout_seconds" to "sessionTimeoutSeconds",
            "state_per_partition_mib" to "statePerPartitionMiB",
            "handoff_bandwidth_mbps" to "handoffBandwidthMbps",
            "deploy_second" to "deploySecond",
            "run_seconds" to "runSeconds",
            "seed" to "seed",
        )

        fun fromQuery(query: Map<String, String>): Config {
            val parsed = query.mapNotNull { (rawKey, rawValue) ->
                val key = queryKeys[rawKey] ?: return@mapNotNull null
                val value = rawValue.toIntOrNull() ?: return@mapNotNull null
                key to value
            }.toMap()
            fun value(key: String, fallback: Int): Int {
                val candidate = parsed[key] ?: return fallback
                return if (candidate > 0) candidate else fallback
            }
            val defaults = Config()
            return Config(
                partitions = value("partitions", defaults.partitions),
                consumers = value("consumers", defaults.consumers),
                restartingConsumers = value("restartingConsumers", defaults.restartingConsumers),
                messagesPerSecond = value("messagesPerSecond", defaults.messagesPerSecond),
                processingPerConsumer = value("processingPerConsumer", defaults.processingPerConsumer),
                restartSeconds = value("restartSeconds", defaults.restartSeconds),
                sessionTimeoutSeconds = value("sessionTimeoutSeconds", defaults.sessionTimeoutSeconds),
                statePerPartitionMiB = value("statePerPartitionMiB", defaults.statePerPartitionMiB),
                handoffBandwidthMbps = value("handoffBandwidthMbps", defaults.handoffBandwidthMbps),
                deploySecond = value("deploySecond", defaults.deploySecond),
                runSeconds = value("runSeconds", defaults.runSeconds),
                seed = value("seed", defaults.seed),
            ).normalized()
        }
    }
}

data class Metrics(
    val rebalances: Int,
    val partitionsRevoked: Int,
    val assignmentChurnPercent: Double,
    val stopTheWorldMs: Int,
    val peakLagMessages: Long,
    val recoverySeconds: Double,
    val duplicateMessages: Int,
    val commitP99Ms: Double,
    val endToEndP99Ms: Double,
    val stateTransferMiB: Double,
    val peakTransferMbps: Double,
) {
    fun toJson(): String = jsonObject(
        "rebalances" to rebalances,
        "partitionsRevoked" to partitionsRevoked,
        "assignmentChurnPercent" to assignmentChurnPercent,
        "stopTheWorldMs" to stopTheWorldMs,
        "peakLagMessages" to peakLagMessages,
        "recoverySeconds" to recoverySeconds,
        "duplicateMessages" to duplicateMessages,
        "commitP99Ms" to commitP99Ms,
        "endToEndP99Ms" to endToEndP99Ms,
        "stateTransferMiB" to stateTransferMiB,
        "peakTransferMbps" to peakTransferMbps,
    )
}

data class TimelinePoint(
    val second: Int,
    val runningConsumers: Int,
    val assignedPartitions: Int,
    val lagMessages: Long,
    val recordsConsumedPerSecond: Int,
    val rebalanceActive: Boolean,
    val rebalanceLatencyMs: Int,
    val assignmentChurnPercent: Double,
    val stateTransferMbps: Double,
    val endToEndP99Ms: Double,
) {
    fun toJson(): String = jsonObject(
        "second" to second,
        "runningConsumers" to runningConsumers,
        "assignedPartitions" to assignedPartitions,
        "lagMessages" to lagMessages,
        "recordsConsumedPerSecond" to recordsConsumedPerSecond,
        "rebalanceActive" to rebalanceActive,
        "rebalanceLatencyMs" to rebalanceLatencyMs,
        "assignmentChurnPercent" to assignmentChurnPercent,
        "stateTransferMbps" to stateTransferMbps,
        "endToEndP99Ms" to endToEndP99Ms,
    )
}

data class TraceEvent(
    val timestampMs: Int,
    val memberId: String,
    val generation: Int,
    val partition: Int,
    val protocol: String,
    val action: String,
    val assignmentState: String,
    val lagMessages: Long,
    val endToEndP99Ms: Double,
    val duplicateRisk: String,
) {
    fun toJson(): String = jsonObject(
        "timestampMs" to timestampMs,
        "memberId" to memberId,
        "generation" to generation,
        "partition" to partition,
        "protocol" to protocol,
        "action" to action,
        "assignmentState" to assignmentState,
        "lagMessages" to lagMessages,
        "endToEndP99Ms" to endToEndP99Ms,
        "duplicateRisk" to duplicateRisk,
    )
}

data class StrategyResult(
    val policy: String,
    val name: String,
    val kicker: String,
    val description: String,
    val tradeoff: String,
    val color: String,
    val recommended: Boolean,
    val metrics: Metrics,
    val timeline: List<TimelinePoint>,
    val events: List<TraceEvent>,
) {
    fun toJson(): String = jsonObject(
        "policy" to policy,
        "name" to name,
        "kicker" to kicker,
        "description" to description,
        "tradeoff" to tradeoff,
        "color" to color,
        "recommended" to recommended,
        "metrics" to RawJson(metrics.toJson()),
        "timeline" to RawJson(timeline.joinToString(prefix = "[", postfix = "]") { it.toJson() }),
        "events" to RawJson(events.joinToString(prefix = "[", postfix = "]") { it.toJson() }),
    )
}

data class SimulationResponse(
    val config: Config,
    val strategies: List<StrategyResult>,
) {
    fun toJson(): String = jsonObject(
        "config" to RawJson(config.toJson()),
        "strategies" to RawJson(strategies.joinToString(prefix = "[", postfix = "]") { it.toJson() }),
    )
}

private enum class Policy {
    EAGER,
    COOPERATIVE,
    STATIC,
    INCREMENTAL,
}

private data class Definition(
    val kind: Policy,
    val policy: String,
    val name: String,
    val kicker: String,
    val description: String,
    val tradeoff: String,
    val color: String,
    val recommended: Boolean,
)

private val definitions = listOf(
    Definition(
        Policy.EAGER,
        "classic_eager",
        "Classic eager",
        "revoke everything · global barrier",
        "Every member revokes every partition before the group computes a new assignment.",
        "Simple semantics permit a complete reshuffle and stop useful work across the group.",
        "#ff5b5b",
        false,
    ),
    Definition(
        Policy.COOPERATIVE,
        "cooperative_sticky",
        "Cooperative sticky",
        "retain ownership · incremental rounds",
        "Members keep unaffected partitions while only the restarted members’ assignments move.",
        "Smaller lag waves can require multiple assignment rounds and careful mixed-client rollout.",
        "#f3a712",
        false,
    ),
    Definition(
        Policy.STATIC,
        "static_membership",
        "Static membership",
        "stable identity · timeout envelope",
        "Restarted instances reclaim the same assignments without a rebalance if they return in time.",
        "Excellent for brief restarts, but exceeding the session timeout falls back to eager failure handling.",
        "#7b61d1",
        false,
    ),
    Definition(
        Policy.INCREMENTAL,
        "incremental_warm_handoff",
        "Incremental warm handoff",
        "new protocol · checkpoint first",
        "Replacement members restore state before a fully incremental ownership handoff.",
        "The gentlest cutover needs overlap capacity, transfer bandwidth, and version-aware ownership fencing.",
        "#26b99a",
        true,
    ),
)

private class DeterministicRandom(seed: Int) {
    private var state = seed.toLong() and 0xffff_ffffL

    fun jitter(magnitude: Double): Double {
        state = (state * 1_664_525L + 1_013_904_223L) and 0xffff_ffffL
        val bucket = (state % 201L).toInt() - 100
        return 1.0 + (bucket / 100.0) * magnitude
    }
}

object Simulator {
    fun simulate(rawConfig: Config = Config()): SimulationResponse {
        val config = rawConfig.normalized()
        return SimulationResponse(
            config,
            definitions.mapIndexed { index, definition ->
                simulateStrategy(config, definition, index)
            },
        )
    }

    private fun simulateStrategy(
        config: Config,
        definition: Definition,
        strategyIndex: Int,
    ): StrategyResult {
        val random = DeterministicRandom(config.seed + strategyIndex * 100_003)
        val movedPartitions = ceil(
            config.partitions * config.restartingConsumers.toDouble() / config.consumers,
        ).toInt()
        val memberFraction = config.restartingConsumers.toDouble() / config.consumers
        val movedStateMiB = movedPartitions * config.statePerPartitionMiB.toDouble()
        val totalStateMiB = config.partitions * config.statePerPartitionMiB.toDouble()
        val transferMiBPerSecond = config.handoffBandwidthMbps / 8.0
        val staticSurvives = config.restartSeconds < config.sessionTimeoutSeconds

        val rebalances: Int
        val revoked: Int
        val churn: Double
        val disruptionSeconds: Double
        val unavailableFraction: Double
        val preDeployTransferMiB: Double
        val peakTransferMbps: Double
        val stopTheWorldMs: Int
        val duplicates: Int

        when (definition.kind) {
            Policy.EAGER -> {
                rebalances = 1
                revoked = config.partitions
                churn = 100.0
                disruptionSeconds =
                    (1.5 + totalStateMiB / transferMiBPerSecond) * random.jitter(0.035)
                unavailableFraction = 1.0
                preDeployTransferMiB = 0.0
                peakTransferMbps = config.handoffBandwidthMbps.toDouble()
                stopTheWorldMs = (disruptionSeconds * 1_000).toInt()
                duplicates = config.partitions * 6
            }

            Policy.COOPERATIVE -> {
                rebalances = 2
                revoked = movedPartitions
                churn = roundTo(memberFraction * 100.0 * random.jitter(0.025), 2)
                disruptionSeconds =
                    (config.restartSeconds + movedStateMiB / transferMiBPerSecond) *
                        random.jitter(0.025)
                unavailableFraction = memberFraction
                preDeployTransferMiB = 0.0
                peakTransferMbps = config.handoffBandwidthMbps.toDouble()
                stopTheWorldMs = 0
                duplicates = movedPartitions * 3
            }

            Policy.STATIC -> {
                rebalances = if (staticSurvives) 0 else 1
                revoked = if (staticSurvives) 0 else config.partitions
                churn = if (staticSurvives) 0.0 else 100.0
                disruptionSeconds = if (staticSurvives) {
                    config.restartSeconds * random.jitter(0.015)
                } else {
                    (1.5 + totalStateMiB / transferMiBPerSecond) * random.jitter(0.035)
                }
                unavailableFraction = if (staticSurvives) memberFraction else 1.0
                preDeployTransferMiB = 0.0
                peakTransferMbps = if (staticSurvives) 0.0 else config.handoffBandwidthMbps.toDouble()
                stopTheWorldMs = if (staticSurvives) 0 else (disruptionSeconds * 1_000).toInt()
                duplicates = if (staticSurvives) 0 else config.partitions * 6
            }

            Policy.INCREMENTAL -> {
                rebalances = 1
                revoked = movedPartitions
                churn = roundTo(memberFraction * 100.0 * random.jitter(0.012), 2)
                val transferSeconds = movedStateMiB / transferMiBPerSecond
                disruptionSeconds = max(0.4, transferSeconds * 0.25)
                unavailableFraction = 0.01
                preDeployTransferMiB = min(
                    movedStateMiB,
                    transferMiBPerSecond * config.deploySecond,
                )
                peakTransferMbps = if (preDeployTransferMiB > 0) {
                    min(
                        config.handoffBandwidthMbps.toDouble(),
                        preDeployTransferMiB * 8.0 / config.deploySecond,
                    )
                } else {
                    0.0
                }
                stopTheWorldMs = 0
                duplicates = movedPartitions
            }
        }

        val capacity = config.consumers * config.processingPerConsumer
        val degradedCapacity = max(
            0,
            ((1.0 - unavailableFraction) * capacity).toInt(),
        )
        val timeline = mutableListOf<TimelinePoint>()
        var lag = 0L
        var peakLag = 0L
        var peakP99 = 0.0
        var peakCommitP99 = 0.0
        var recoveredAt: Int? = null
        val activeSeconds = ceil(disruptionSeconds).toInt()

        for (second in 0 until config.runSeconds) {
            val elapsed = second - config.deploySecond
            val rebalanceActive = elapsed in 0 until activeSeconds
            val currentCapacity = when {
                !rebalanceActive -> capacity
                definition.kind == Policy.INCREMENTAL && elapsed > 0 -> capacity
                else -> degradedCapacity
            }
            lag += config.messagesPerSecond.toLong()
            val consumed = min(lag, currentCapacity.toLong()).toInt()
            lag -= consumed
            peakLag = max(peakLag, lag)

            val backlogSeconds = lag / config.messagesPerSecond.toDouble()
            val endToEndP99 = roundTo(35.0 + backlogSeconds * 1_000.0, 2)
            val commitP99 = roundTo(
                12.0 + (if (rebalanceActive) churn * 0.55 else 0.0) + backlogSeconds * 18.0,
                2,
            )
            peakP99 = max(peakP99, endToEndP99)
            peakCommitP99 = max(peakCommitP99, commitP99)
            if (
                elapsed >= activeSeconds &&
                lag == 0L &&
                recoveredAt == null
            ) {
                recoveredAt = second
            }

            val transferMbps = when {
                definition.kind == Policy.INCREMENTAL && second < config.deploySecond ->
                    peakTransferMbps
                rebalanceActive && definition.kind != Policy.STATIC ->
                    peakTransferMbps
                else -> 0.0
            }
            val runningConsumers = when {
                !rebalanceActive -> config.consumers
                definition.kind == Policy.INCREMENTAL -> config.consumers +
                    config.restartingConsumers
                else -> config.consumers - config.restartingConsumers
            }
            val assignedPartitions = when {
                !rebalanceActive -> config.partitions
                definition.kind == Policy.EAGER ||
                    (definition.kind == Policy.STATIC && !staticSurvives) -> 0
                else -> config.partitions - movedPartitions
            }

            timeline += TimelinePoint(
                second = second,
                runningConsumers = runningConsumers,
                assignedPartitions = assignedPartitions,
                lagMessages = lag,
                recordsConsumedPerSecond = consumed,
                rebalanceActive = rebalanceActive,
                rebalanceLatencyMs = if (rebalanceActive) (disruptionSeconds * 1_000).toInt() else 0,
                assignmentChurnPercent = if (second == config.deploySecond) churn else 0.0,
                stateTransferMbps = roundTo(transferMbps, 1),
                endToEndP99Ms = endToEndP99,
            )
        }

        val eventIndices = listOf(
            0,
            max(0, config.deploySecond - 1),
            config.deploySecond,
            min(config.runSeconds - 1, config.deploySecond + 1),
            min(config.runSeconds - 1, config.deploySecond + activeSeconds),
            min(config.runSeconds - 1, config.deploySecond + activeSeconds + 5),
            config.runSeconds - 1,
        ).distinct()
        val events = eventIndices.mapIndexed { index, second ->
            val point = timeline[second]
            val action = when {
                second < config.deploySecond && definition.kind == Policy.INCREMENTAL ->
                    "checkpoint-copy"
                second < config.deploySecond -> "poll"
                second == config.deploySecond -> "assignment-change"
                point.rebalanceActive -> "restore-and-poll"
                point.lagMessages > 0 -> "drain-lag"
                else -> "steady"
            }
            TraceEvent(
                timestampMs = second * 1_000,
                memberId = "consumer-${(index % config.consumers) + 1}",
                generation = when {
                    second < config.deploySecond -> 41
                    definition.kind == Policy.STATIC && staticSurvives -> 41
                    else -> 42
                },
                partition = (index * 7 + strategyIndex * 3) % config.partitions,
                protocol = definition.policy,
                action = action,
                assignmentState = when {
                    point.assignedPartitions == 0 -> "revoked"
                    point.rebalanceActive -> "transferring"
                    else -> "owned"
                },
                lagMessages = point.lagMessages,
                endToEndP99Ms = point.endToEndP99Ms,
                duplicateRisk = when {
                    duplicates == 0 -> "none"
                    action == "assignment-change" -> "commit-gap"
                    else -> "bounded"
                },
            )
        }
        val recoverySeconds = recoveredAt?.let {
            max(0, it - config.deploySecond).toDouble()
        } ?: (config.runSeconds - config.deploySecond).toDouble()

        return StrategyResult(
            policy = definition.policy,
            name = definition.name,
            kicker = definition.kicker,
            description = definition.description,
            tradeoff = definition.tradeoff,
            color = definition.color,
            recommended = definition.recommended,
            metrics = Metrics(
                rebalances = rebalances,
                partitionsRevoked = revoked,
                assignmentChurnPercent = churn,
                stopTheWorldMs = stopTheWorldMs,
                peakLagMessages = peakLag,
                recoverySeconds = roundTo(recoverySeconds, 1),
                duplicateMessages = duplicates,
                commitP99Ms = peakCommitP99,
                endToEndP99Ms = peakP99,
                stateTransferMiB = roundTo(
                    if (definition.kind == Policy.INCREMENTAL) preDeployTransferMiB
                    else if (definition.kind == Policy.STATIC && staticSurvives) 0.0
                    else if (definition.kind == Policy.EAGER) totalStateMiB
                    else movedStateMiB,
                    1,
                ),
                peakTransferMbps = roundTo(peakTransferMbps, 1),
            ),
            timeline = timeline,
            events = events,
        )
    }
}

private data class RawJson(val value: String)

private fun roundTo(value: Double, places: Int): Double {
    val factor = 10.0.pow(places)
    return round(value * factor) / factor
}

private fun jsonEscape(value: String): String = buildString {
    for (character in value) {
        append(
            when (character) {
                '\\' -> "\\\\"
                '"' -> "\\\""
                '\n' -> "\\n"
                '\r' -> "\\r"
                '\t' -> "\\t"
                else -> character
            },
        )
    }
}

private fun jsonValue(value: Any?): String = when (value) {
    null -> "null"
    is RawJson -> value.value
    is String -> "\"${jsonEscape(value)}\""
    is Boolean, is Number -> value.toString()
    else -> error("Unsupported JSON value: ${value::class}")
}

private fun jsonObject(vararg fields: Pair<String, Any?>): String =
    fields.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        "\"${jsonEscape(key)}\":${jsonValue(value)}"
    }
