package androidx.activity
open class ComponentActivity : android.app.Activity(), androidx.lifecycle.LifecycleOwner {
    override val lifecycle: androidx.lifecycle.Lifecycle = androidx.lifecycle.Lifecycle()
    fun <I, O> registerForActivityResult(
        contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
        callback: androidx.activity.result.ActivityResultCallback<O>
    ): androidx.activity.result.ActivityResultLauncher<I> = TODO()
}
