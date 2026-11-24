package com.askfirst

import android.app.Application
import com.askfirst.core.AndroidLogx
import com.askfirst.core.CrashLogger
import com.askfirst.di.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AskFirstApp : Application() {

    /** 进程级依赖容器。禁止在 Activity 里重建（会开第二个 Room 实例）。 */
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 不上架也不接崩溃平台：闪退必须能在本地留下日志（方案 §七.3）
        CrashLogger.install(this, BuildConfig.VERSION_NAME)
        container = AppContainer(this, appScope, AndroidLogx)
        // 灌库在后台进行；UI 通过 container.seedResult 等待
        container.warmUp()
    }
}