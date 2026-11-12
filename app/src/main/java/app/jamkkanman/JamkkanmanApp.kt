package app.jamkkanman

import android.app.Application

class JamkkanmanApp : Application() {
    /** One engine for the whole process, so a screen rotation or recreation does not reset a stop. */
    val host: EngineHost by lazy { EngineHost(this) }
}
