package com.dsh.android.account

/** Serializes account transitions across activities and delayed network work. */
class AccountChanges {
    private var generation = 0L

    @Synchronized
    fun begin(): Attempt = Attempt(++generation)

    inner class Attempt internal constructor(private val owner: Long) {
        private var consumed = false
        val isCurrent: Boolean get() = synchronized(this@AccountChanges) { !consumed && generation == owner }

        fun cancel() = synchronized(this@AccountChanges) { consumed = true }

        /** The current check and credential/mode writes share the same lock as cancellation and supersession. */
        fun commit(action: () -> Unit): Boolean = synchronized(this@AccountChanges) {
            if (!isCurrent) return false
            consumed = true
            action()
            true
        }
    }

    companion object { val shared = AccountChanges() }
}
