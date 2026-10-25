package io.github.lejw0925.virtualscreen.phone.embedded

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Exported only behind DUMP; additionally requires shell UID and a live one-shot bootstrap nonce. */
class EmbeddedBridgeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 2000) { "ADB_SHELL_IDENTITY_MISMATCH" }
        require(method == "register" && extras != null) { "INVALID_BRIDGE_REQUEST" }
        val lifetime = EmbeddedPrivilege.accept(extras.getString("nonce"), requireNotNull(extras.getBinder("service")))
        return Bundle().apply { putBinder("owner", lifetime) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
