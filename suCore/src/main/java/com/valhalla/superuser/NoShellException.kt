package com.valhalla.superuser

/**
 * Thrown when it is impossible to construct `Shell`.
 * This is a runtime exception, and should happen very rarely.
 */
class NoShellException : RuntimeException {
    constructor(msg: String?) : super(msg)

    constructor(message: String?, cause: Throwable?) : super(message, cause)
}
