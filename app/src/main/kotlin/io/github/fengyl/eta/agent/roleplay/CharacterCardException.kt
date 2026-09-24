package io.github.fengyl.eta.agent.roleplay

internal class CharacterCardException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
