package com.tex159.missedcallbot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.telephony.TelephonyManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

object CallMonitorHolder {
    private var lastState = TelephonyManager.CALL_STATE_IDLE
    private var incomingNumber: String? = null
    private var wasRinging = false
    private var receiver: BroadcastReceiver? = null
    private var isRegistered = false

    fun start(context: Context) {
        if (isRegistered) return
        val appCtx = context.applicationContext
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
                val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                val num = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                if (!num.isNullOrBlank()) {
                    incomingNumber = num
                }

                val state = when (stateStr) {
                    TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
                    TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
                    else -> TelephonyManager.CALL_STATE_IDLE
                }

                if (state == lastState) return

                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> wasRinging = true
                    TelephonyManager.CALL_STATE_OFFHOOK -> wasRinging = false
                    TelephonyManager.CALL_STATE_IDLE -> {
                        val missed = incomingNumber
                        if (wasRinging && !missed.isNullOrBlank()) {
                            schedule2MinCheck(appCtx, missed, System.currentTimeMillis())
                        }
                        wasRinging = false
                        incomingNumber = null
                    }
                }
                lastState = state
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            appCtx.registerReceiver(receiver, filter)
        }
        isRegistered = true
    }

    private fun schedule2MinCheck(context: Context, number: String, missedTime: Long) {
        val data = Data.Builder()
            .putString(MissedCallWorker.KEY_NUM, number)
            .putLong(MissedCallWorker.KEY_TIME, missedTime)
            .build()

        val req = OneTimeWorkRequestBuilder<MissedCallWorker>()
            .setInitialDelay(2, TimeUnit.MINUTES)
            .setInputData(data)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "missed_call_${missedTime}",
            ExistingWorkPolicy.REPLACE,
            req
        )
    }
}

class MissedCallWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_NUM = "key_num"
        const val KEY_TIME = "key_time"
    }

    override suspend fun doWork(): Result {
        val num = inputData.getString(KEY_NUM) ?: return Result.success()
        val missedTime = inputData.getLong(KEY_TIME, 0L)

        val hasCallLog = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCallLog && alreadyCalledBack(applicationContext, num, missedTime)) {
            return Result.success()
        }

        val hasContacts = ContextCompat.checkSelfPermission(
            applicationContext, Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        val name = if (hasContacts) findContactName(applicationContext, num) else null

        val msg = if (!name.isNullOrBlank()) {
            "দোস্ত, প্রায় ২ মিনিট আগে $name তোমাকে কল দিয়েছিল, হয়তো ব্যস্ততা বা সাইলেন্ট থাকার কারণে ধরতে পারোনি।"
        } else {
            "দোস্ত, প্রায় ২ মিনিট আগে একটা আননোন নম্বর থেকে কল এসেছিল, হয়তো ব্যস্ততার কারণে ধরতে পারোনি।"
        }

        withTimeoutOrNull(25_000L) {
            speakDone(applicationContext, msg)
        }

        return Result.success()
    }

    private fun alreadyCalledBack(context: Context, phone: String, missedTime: Long): Boolean {
        return try {
            val proj = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE)
            val sel = "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.TYPE} = ?"
            val args = arrayOf(missedTime.toString(), CallLog.Calls.OUTGOING_TYPE.toString())
            context.contentResolver.query(CallLog.Calls.CONTENT_URI, proj, sel, args, null)?.use { c ->
                val target = phone.replace(Regex("[^0-9]"), "").takeLast(10)
                while (c.moveToNext()) {
                    val logNum = c.getString(0)?.replace(Regex("[^0-9]"), "") ?: ""
                    if (target.isNotEmpty() && logNum.endsWith(target)) return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun findContactName(context: Context, phone: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phone)
            )
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun speakDone(context: Context, text: String) =
        suspendCancellableCoroutine<Unit> { cont ->
            var done = false
            fun finish() {
                if (!done) {
                    done = true
                    if (cont.isActive) cont.resume(Unit)
                }
            }

            var tts: TextToSpeech? = null
            tts = TextToSpeech(context) { status ->
                if (status != TextToSpeech.SUCCESS || tts == null) {
                    finish()
                    return@TextToSpeech
                }
                val engine = tts!!
                val res = engine.setLanguage(Locale("bn", "BD"))
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.language = Locale.US
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        try { engine.stop(); engine.shutdown() } catch (_: Exception) {}
                        finish()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        try { engine.stop(); engine.shutdown() } catch (_: Exception) {}
                        finish()
                    }
                })
                val code = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "CALL_REMINDER")
                if (code == TextToSpeech.ERROR) finish()
            }

            cont.invokeOnCancellation {
                try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
                finish()
            }
        }
}

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        checkPermissionsAndStart()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            textSize = 17f
            setPadding(32, 60, 32, 24)
            text = "অবস্থা: পারমিশন চেক করা হচ্ছে..."
        }

        val permBtn = Button(this).apply {
            text = "পারমিশন দিন"
            setOnClickListener { askPermissions() }
        }

        val batteryBtn = Button(this).apply {
            text = "Battery Optimization বন্ধ করুন"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                } catch (_: Exception) {
                }
            }
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(statusText)
            addView(permBtn)
            addView(batteryBtn)
        }

        setContentView(box)
        checkPermissionsAndStart()
    }

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return list.toTypedArray()
    }

    private fun askPermissions() {
        permLauncher.launch(neededPermissions())
    }

    private fun checkPermissionsAndStart() {
        val ok = neededPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (ok) {
            CallMonitorHolder.start(this)
            statusText.text = "অবস্থা: চালু আছে\nমিসড কল হলে ২ মিনিট পর ভয়েসে জানাবে।"
        } else {
            statusText.text = "অবস্থা: পারমিশন বাকি আছে\n'পারমিশন দিন' বাটনে চাপুন।"
        }
    }
}
