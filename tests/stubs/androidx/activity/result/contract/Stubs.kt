package androidx.activity.result.contract
abstract class ActivityResultContract<I, O>
class ActivityResultContracts {
    class OpenDocumentTree : ActivityResultContract<android.net.Uri?, android.net.Uri?>()
    class OpenDocument : ActivityResultContract<Array<String>, android.net.Uri?>()
}
