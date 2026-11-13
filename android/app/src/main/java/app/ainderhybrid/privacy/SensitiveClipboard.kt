package app.ainderhybrid.privacy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle

/**
 * Copies a secret (the `aind_` key) marked as sensitive, so the clipboard preview and keyboard
 * suggestions hide it, and clears it again after [CLEAR_AFTER_MS] if it is still our clip.
 */
object SensitiveClipboard {
    const val CLEAR_AFTER_MS = 60_000L
    const val LABEL = "ainder API key"
    /** Same value as ClipDescription.EXTRA_IS_SENSITIVE (API 33); older versions read the literal. */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun copy(context: Context, secret: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(LABEL, secret)
        clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        Handler(Looper.getMainLooper()).postDelayed({ clearIfOurs(cm) }, CLEAR_AFTER_MS)
    }

    /** Clears only our own sensitive clip, never something the user copied afterwards. */
    fun isOurClip(label: CharSequence?, sensitive: Boolean): Boolean = sensitive && label?.toString() == LABEL

    private fun clearIfOurs(cm: ClipboardManager) {
        val d = runCatching { cm.primaryClipDescription }.getOrNull() ?: return
        if (!isOurClip(d.label, d.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) cm.clearPrimaryClip()
        else cm.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
