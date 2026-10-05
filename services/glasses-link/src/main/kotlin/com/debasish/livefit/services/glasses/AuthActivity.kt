package com.debasish.livefit.services.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Runs Hi Rokid authorization in-process (CXR-L needs an Activity) and closes itself. */
class AuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = CxrGlassesLink.instance ?: return finish()
        link.authorize(this) { ok ->
            if (ok) link.markAuthorized()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        CxrGlassesLink.instance?.let { link ->
            val r = runCatching { com.rokid.cxr.session.CxrSessionManager.getInstance(this).parseAuthorizationResult(resultCode, data) }.getOrNull()
            if (r?.isSuccess == true) link.markAuthorized()
        }
        finish()
    }

    companion object {
        fun launch(context: Context) = runCatching {
            context.startActivity(Intent(context, AuthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }
}
