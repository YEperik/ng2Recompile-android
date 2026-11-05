package androidx.core.view
import android.view.View
import android.view.Window
object WindowCompat { @JvmStatic fun setDecorFitsSystemWindows(window: Window, decorFitsSystemWindows: Boolean) {} }
class WindowInsetsCompat { object Type { @JvmStatic fun systemBars(): Int = 0 } }
class WindowInsetsControllerCompat(window: Window, view: View) {
    var systemBarsBehavior: Int = 0
    fun hide(types: Int) {}
    companion object { const val BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE = 2 }
}
