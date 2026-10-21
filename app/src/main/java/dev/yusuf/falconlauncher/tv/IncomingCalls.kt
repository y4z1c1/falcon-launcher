package dev.yusuf.falconlauncher.tv

import android.os.Handler
import android.os.Looper

/**
 * Turns ANCS notification events into call muting:
 *  - an "Incoming Call" notification appears (the phone starts ringing) → mute;
 *  - an "Active Call" notification appears (a call is answered or placed) → stay muted while it's there;
 *  - the active call's notification disappears (hung up) → restore;
 *  - ringing stops with no active call (missed or declined) → restore.
 *
 * Notification ids change when the iPhone reconnects, so calls are tracked by category, not by id.
 */
class IncomingCalls(private val callMute: CallMute, private val enabled: () -> Boolean, private val toast: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var ringing = false
    private var inCall = false

    private val ringingStopped = Runnable {
        if (!inCall && callMute.end("missed call")) toast("TV sound restored")
    }

    fun onEvent(e: AncsClient.AncsEvent) {
        val added = e.event == AncsClient.EVENT_ADDED
        val removed = e.event == AncsClient.EVENT_REMOVED
        when (e.category) {
            AncsClient.CATEGORY_INCOMING_CALL -> when {
                added -> {
                    ringing = true
                    main.removeCallbacks(ringingStopped)
                    mute("Incoming call · TV muted")
                }
                removed && ringing -> {
                    ringing = false
                    // When answered, the "Active Call" notification lands a moment later; wait for it.
                    main.postDelayed(ringingStopped, DECIDE_DELAY_MS)
                }
            }
            AncsClient.CATEGORY_ACTIVE_CALL -> when {
                added -> {
                    inCall = true
                    main.removeCallbacks(ringingStopped)
                    mute("On a call · TV muted")
                    callMute.extend(CallMute.SAFETY_MS)
                }
                removed && inCall -> {
                    inCall = false
                    if (!ringing && callMute.end("call ended")) toast("TV sound restored")
                }
            }
        }
    }

    private fun mute(message: String) {
        if (!enabled() || callMute.active) return
        callMute.start()
        toast(message)
    }

    private companion object {
        const val DECIDE_DELAY_MS = 2_500L
    }
}
