package com.sanmer.mrepo.provider

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.sanmer.mrepo.BuildConfig
import com.sanmer.mrepo.api.ApiInitializerListener
import com.sanmer.mrepo.api.local.LocalApi
import com.sanmer.mrepo.app.Event
import com.sanmer.mrepo.utils.extensions.toFile
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SuProviderImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : SuProvider {
    override val state = MutableStateFlow(Event.NON)
    private val listener = object : ApiInitializerListener {
        override fun onSuccess() {
            state.value = Event.SUCCEEDED
            Timber.i("SuProviderImpl created")
        }

        override fun onFailure() {
            state.value = Event.FAILED
            Timber.w("SuProviderImpl destroyed")
        }
    }

    private lateinit var mProvider: ISuProvider
    private lateinit var mApi: LocalApi
    override val isInitialized get() =
        ::mProvider.isInitialized && ::mApi.isInitialized

    private val uid by lazy { context.applicationInfo.uid }

    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setInitializers(SuShellInitializer::class.java)
                .setFlags(Shell.FLAG_REDIRECT_STDERR or Shell.FLAG_MOUNT_MASTER)
                .setTimeout(15)
        )
    }

    private class SuShellInitializer : Shell.Initializer() {
        override fun onInit(context: Context, shell: Shell): Boolean = true
    }

    fun init() {
        Timber.d("SuProviderImpl init")

        runCatching {
            if (!Shell.getShell().isRoot) {
                Timber.e("su request rejected ($uid)")
                return@runCatching
            }

            Intent(context, SuService::class.java).apply {
                RootService.bind(this, connection)
            }
        }.onFailure {
            Timber.e(it, "SuProviderImpl init")
            listener.onFailure()
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            mProvider = ISuProvider.Stub.asInterface(binder)

            runCatching {
                mApi = LocalApi.build(
                    context = context,
                    attr = mProvider.context,
                    listener = listener,
                    fs = fs
                )
            }.onFailure {
                Timber.e(it)
                listener.onFailure()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            listener.onFailure()
        }
    }

    private class SuService : RootService() {
        override fun onBind(intent: Intent): IBinder = object : ISuProvider.Stub() {
            override fun getContext(): String = getContextImpl()
            override fun getFileSystemService(): IBinder = FileSystemManager.getService()
        }

        @Suppress("SameParameterValue")
        private inline fun <T> safe(default: T, block: () -> T): T {
            return try {
                block()
            } catch (e: Throwable) {
                Timber.e(e)
                default
            }
        }

        private fun getContextImpl() = safe("unknown") {
            "/proc/self/attr/current".toFile()
                .readText()
                .replace("[^a-z0-9:_,]".toRegex(), "")
        }
    }

    override val fs get() = FileSystemManager.getRemote(mProvider.fileSystemService)
    override val api get() = mApi

}