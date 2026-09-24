package io.github.mangi.eta.agent.display

internal object DisplayProtocol {
    const val VERSION = 2
    const val PACKAGE = "io.github.mangi.eta"
    const val ACTION = "$PACKAGE.action.CONNECT_WORK_DISPLAY"
    const val PERMISSION = "$PACKAGE.permission.CONTROL_WORK_DISPLAY"
    const val DESCRIPTOR = "$PACKAGE.WorkDisplay.v2"
    const val TRANSACT = android.os.IBinder.FIRST_CALL_TRANSACTION
    // These are VirtualDisplay creation flags, NOT Display.flags.
    const val CREATE_FLAGS = (1 shl 0) or (1 shl 3) or (1 shl 6) or (1 shl 8) or
        (1 shl 10) or (1 shl 11) or (1 shl 14) or (1 shl 16)
    const val REQUIRED_DISPLAY_FLAGS = (1 shl 7) or (1 shl 11) or (1 shl 12)
    const val WIDTH = 900
    const val HEIGHT = 1600
    const val DENSITY = 320
}
