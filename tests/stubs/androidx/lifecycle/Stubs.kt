package androidx.lifecycle
open class Lifecycle
interface LifecycleOwner { val lifecycle: Lifecycle }
val LifecycleOwner.lifecycleScope: kotlinx.coroutines.CoroutineScope get() = TODO()
