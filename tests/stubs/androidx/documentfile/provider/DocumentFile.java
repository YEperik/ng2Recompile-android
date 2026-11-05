package androidx.documentfile.provider;
public abstract class DocumentFile {
    public abstract String getName();
    public abstract boolean isDirectory();
    public abstract boolean isFile();
    public abstract long length();
    public abstract android.net.Uri getUri();
    public abstract DocumentFile[] listFiles();
    public static DocumentFile fromTreeUri(android.content.Context context, android.net.Uri uri) { return null; }
}
