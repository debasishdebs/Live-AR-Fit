package com.debasish.livefit.phone

import android.content.Context
import android.content.Intent
import com.debasish.livefit.sync.CrashLog
import java.io.File

/** Spec §6: the phone's crash log and its share sheet; nothing is sent automatically. */
object PhoneCrash {
    fun log(context: Context): CrashLog = CrashLog(File(context.applicationContext.filesDir, CrashLog.FILE_NAME), BuildConfig.VERSION_NAME)

    fun shareIntent(text: String): Intent {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Live AR Fit crash report")
            .putExtra(Intent.EXTRA_TEXT, text)
        return Intent.createChooser(send, "Share last crash").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
