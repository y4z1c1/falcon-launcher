package dev.yusuf.falconlauncher.tv

import android.os.Handler
import android.os.Looper

/**
 * Turns ANCS notification events into call muting:
 *  - an "Incoming Call" notification appears (the phone starts ringing) → pause and mute;
 *  - an "Active Call" notification appears (a call is answered or placed) → stay so while it's there;
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
        if (!inCall) restore("missed call")
    }

    fun onEvent(e: AncsClient.AncsEvent) {
        val added = e.event == AncsClient.EVENT_ADDED
        val removed = e.event == AncsClient.EVENT_REMOVED
        when (e.category) {
            AncsClient.CATEGORY_INCOMING_CALL -> when {
                added -> {
                    ringing = true
                    main.removeCallbacks(ringingStopped)
                    mute("Incoming call")
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
                    mute("On a call")
                    callMute.extend(CallMute.SAFETY_MS)
                }
                removed && inCall -> {
                    inCall = false
                    if (!ringing) restore("call ended")
                }
            }
        }
    }

    private fun mute(what: String) {
        if (!enabled() || callMute.active) return
        callMute.start()
        toast(if (callMute.pausedMedia) "$what · Paused and muted" else "$what · TV muted")
    }

    private fun restore(reason: String) {
        val resumes = callMute.pausedMedia
        if (callMute.end(reason)) toast(if (resumes) "Playing again" else "TV sound restored")
    }

    private companion object {
        const val DECIDE_DELAY_MS = 2_500L
    }
}
