package dev.hermeskotlin.core.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Where the device says it moved to another network. A socket opened on the old one is gone, but a half-open
 * connection never reports itself dead, so the gateway has to be told to start over.
 */
fun interface NetworkChanges {
    fun changes(): Flow<Unit>
}

/** Nothing moves the radio on a machine that sits on one wire. */
object NoNetworkChanges : NetworkChanges {
    override fun changes(): Flow<Unit> = emptyFlow()
}
