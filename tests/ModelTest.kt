package lab

private fun strategy(response: SimulationResponse, policy: String): StrategyResult =
    response.strategies.first { it.policy == policy }

private fun assertThat(condition: Boolean, message: String) {
    if (!condition) error(message)
}

fun main() {
    val response = Simulator.simulate()
    val eager = strategy(response, "classic_eager")
    val cooperative = strategy(response, "cooperative_sticky")
    val static = strategy(response, "static_membership")
    val incremental = strategy(response, "incremental_warm_handoff")

    assertThat(response.strategies.size == 4, "expected four strategies")
    assertThat(
        eager.metrics.partitionsRevoked == response.config.partitions,
        "eager must revoke the full assignment",
    )
    assertThat(eager.metrics.stopTheWorldMs > 0, "eager must expose a global pause")
    assertThat(
        cooperative.metrics.peakLagMessages < eager.metrics.peakLagMessages,
        "cooperative assignment should reduce the lag wave",
    )
    assertThat(static.metrics.rebalances == 0, "brief static restart should avoid a rebalance")
    assertThat(
        static.events.all { it.generation == 41 },
        "static identity recovery should retain the group generation",
    )
    assertThat(
        incremental.metrics.peakLagMessages < static.metrics.peakLagMessages,
        "warm handoff should beat an unavailable static member",
    )
    assertThat(
        incremental.metrics.endToEndP99Ms < eager.metrics.endToEndP99Ms,
        "incremental handoff should reduce request tails",
    )

    val expiredStatic = strategy(
        Simulator.simulate(
            Config(
                restartSeconds = 12,
                sessionTimeoutSeconds = 10,
            ),
        ),
        "static_membership",
    )
    assertThat(expiredStatic.metrics.rebalances == 1, "expired static member must rebalance")
    assertThat(
        expiredStatic.metrics.partitionsRevoked == response.config.partitions,
        "expired static member must fall back to full failure handling",
    )
    assertThat(
        expiredStatic.events.any { it.generation == 42 },
        "expired static member must advance the generation",
    )

    val normalized = Config.fromQuery(
        mapOf(
            "partitions" to "1",
            "consumers" to "999",
            "restarting_consumers" to "999",
            "run_seconds" to "999",
            "deploy_second" to "999",
            "seed" to "0",
        ),
    )
    assertThat(normalized.partitions == 4, "partitions must clamp")
    assertThat(normalized.consumers == 64, "consumers must clamp")
    assertThat(normalized.restartingConsumers == 64, "restart count must clamp to consumers")
    assertThat(normalized.runSeconds == 180, "run seconds must clamp")
    assertThat(normalized.deploySecond == 170, "deploy second must clamp after runtime")
    assertThat(normalized.seed == Config().seed, "zero seed must fall back")

    val first = Simulator.simulate().toJson()
    val second = Simulator.simulate().toJson()
    assertThat(first == second, "same seed must produce byte-identical JSON")

    println("ModelTest: 18 assertions passed")
}
