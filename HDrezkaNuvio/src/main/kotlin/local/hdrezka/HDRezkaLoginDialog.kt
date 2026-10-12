package local.hdrezka

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import android.util.Log
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object HDRezkaLoginDialog {
    @Volatile
    private var shown = false
    @Volatile
    private var waitingForActivity = false
    private var activityReference = WeakReference<Activity>(null)

    fun showIfNeeded(context: Context) {
        val activity = (context as? Activity) ?: activityReference.get()
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            activityReference = WeakReference(activity)
        }
        if (shown) return
        val hasSession = HDRezkaSession.hasSession()
        Log.d("HDRezkaAuth", "prompt context=${context.javaClass.simpleName}, session=$hasSession")
        if (hasSession) return
        if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
            activity.runOnUiThread {
                if (shown || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val claimed = HDRezkaSession.claimLoginPrompt()
                Log.d("HDRezkaAuth", "prompt claimed=$claimed")
                if (claimed) show(activity)
            }
            return
        }
        val application = context.applicationContext as? Application ?: return
        if (waitingForActivity) return
        waitingForActivity = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                application.unregisterActivityLifecycleCallbacks(this)
                waitingForActivity = false
                showIfNeeded(activity)
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    fun show(activity: Activity) {
        activity.runOnUiThread {
            if (shown || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            shown = true
            val padding = (24 * activity.resources.displayMetrics.density).toInt()
            val login = EditText(activity).apply {
                hint = "Логин или электронная почта"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                isSingleLine = true
            }
            val password = EditText(activity).apply {
                hint = "Пароль"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true
            }
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, 0, padding, 0)
                addView(login, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(password, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle("HDRezka")
                .setMessage("rezka.fi требует вход для просмотра. Сеанс сохраняется на телевизоре; пароль не сохраняется. Озвучки Premium доступны только с подпиской сайта.")
                .setView(content)
                .setNegativeButton("Без входа") { _, _ -> HDRezkaSession.useGuestMode() }
                .setPositiveButton("Войти", null)
                .create()

            dialog.setOnCancelListener { HDRezkaSession.useGuestMode() }
            dialog.setOnDismissListener { shown = false }

            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val enteredLogin = login.text.toString().trim()
                    val enteredPassword = password.text.toString()
                    if (enteredLogin.isBlank() || enteredPassword.isBlank()) {
                        Toast.makeText(activity, "Введите логин и пароль", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    CoroutineScope(Dispatchers.IO).launch {
                        val success = runCatching {
                            HDRezkaSession.login(enteredLogin, enteredPassword)
                        }.onFailure {
                            Log.e("HDRezkaAuth", "Login failed: ${it.javaClass.simpleName}")
                        }.getOrDefault(false)
                        withContext(Dispatchers.Main) {
                            password.text?.clear()
                            if (success) {
                                Toast.makeText(activity, "Вход выполнен", Toast.LENGTH_SHORT).show()
                                dialog.dismiss()
                            } else {
                                Toast.makeText(activity, "Не удалось войти", Toast.LENGTH_SHORT).show()
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            }
                        }
                    }
                }
            }
            dialog.show()
        }
    }
}
