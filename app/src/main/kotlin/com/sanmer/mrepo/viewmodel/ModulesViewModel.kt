package com.sanmer.mrepo.viewmodel

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.text.style.TextDecoration
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sanmer.mrepo.datastore.modules.ModulesMenuExt
import com.sanmer.mrepo.datastore.repository.Option
import com.sanmer.mrepo.model.json.UpdateJson
import com.sanmer.mrepo.model.local.LocalModule
import com.sanmer.mrepo.model.local.State
import com.sanmer.mrepo.model.online.VersionItem
import com.sanmer.mrepo.provider.ProviderCompat
import com.sanmer.mrepo.repository.LocalRepository
import com.sanmer.mrepo.repository.ModulesRepository
import com.sanmer.mrepo.repository.UserPreferencesRepository
import com.sanmer.mrepo.service.DownloadService
import com.sanmer.mrepo.utils.Utils
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.sanmer.mrepo.compat.stub.IModuleOpsCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ModulesViewModel @Inject constructor(
    private val localRepository: LocalRepository,
    private val modulesRepository: ModulesRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {
    val isProviderAlive get() = ProviderCompat.isAlive

    val isProviderKsu get() = when {
        isProviderAlive -> ProviderCompat.isKsu
        else -> false
    }

    private val modulesMenu get() = userPreferencesRepository.data
        .map { it.modulesMenu }

    var isSearch by mutableStateOf(false)
        private set
    private val keyFlow = MutableStateFlow("")

    private val valuesFlow = MutableStateFlow(
        listOf<LocalModule>()
    )
    val local get() = valuesFlow.asStateFlow()

    private var oneTimeFinished = false
    var isLoading by mutableStateOf(true)
        private set
    var isRefreshing by mutableStateOf(false)
        private set
    private inline fun <T> T.refreshing(callback: T.() -> Unit) {
        isRefreshing = true
        callback()
        isRefreshing = false
    }

    private val versionItemCache = mutableStateMapOf<String, VersionItem?>()

    private val opsTasks = mutableStateListOf<String>()
    val isOpsRunning get() = opsTasks.isNotEmpty()
    private val opsCallback = object : IModuleOpsCallback.Stub() {
        override fun onSuccess(id: String) {
            viewModelScope.launch {
                modulesRepository.getLocal(id)
                opsTasks.remove(id)
            }
        }

        override fun onFailure(id: String, msg: String?) {
            opsTasks.remove(id)
            Timber.w("$id: $msg")
        }
    }

    init {
        Timber.d("ModulesViewModel init")
        dataObserver()
    }

    fun loadDataOneTime() {
        viewModelScope.launch {
            if (!oneTimeFinished && isProviderAlive) {
                modulesRepository.getLocalAll()
                oneTimeFinished = true
            }
        }
    }

    private fun dataObserver() {
        combine(
            localRepository.getLocalAllAsFlow(),
            modulesMenu,
            keyFlow,
        ) { list, menu, key ->
            if (list.isEmpty()) return@combine

            valuesFlow.value  = list.sortedWith(
                comparator(menu.option, menu.descending)
            ).let { v ->
                if (menu.pinEnabled) {
                    v.sortedByDescending { it.state == State.ENABLE }
                } else {
                    v
                }
            }.filter { m ->
                if (key.isBlank()) return@filter true
                key.lowercase() in (m.name + m.author + m.description).lowercase()

            }.toMutableStateList()

            isLoading = false

        }.launchIn(viewModelScope)
    }

    private fun comparator(
        option: Option,
        descending: Boolean
    ): Comparator<LocalModule> = if (descending) {
        when (option) {
            Option.NAME -> compareByDescending { it.name.lowercase() }
            Option.UPDATED_TIME -> compareBy { it.lastUpdated }
            else -> compareByDescending { null }
        }

    } else {
        when (option) {
            Option.NAME -> compareBy { it.name.lowercase() }
            Option.UPDATED_TIME -> compareByDescending { it.lastUpdated }
            else -> compareByDescending { null }
        }
    }

    fun search(key: String) {
        keyFlow.value = key
    }

    fun openSearch() {
        isSearch = true
    }

    fun closeSearch() {
        isSearch = false
        keyFlow.value = ""
    }

    fun getLocalAll() {
        viewModelScope.launch {
            refreshing {
                modulesRepository.getLocalAll()
            }
        }
    }

    fun setModulesMenu(value: ModulesMenuExt) =
        userPreferencesRepository.setModulesMenu(value)

    private fun createUiState(module: LocalModule) = when (module.state) {
            State.ENABLE -> LocalUiState(
                alpha = 1f,
                decoration = TextDecoration.None,
                toggle = {
                    opsTasks.add(module.id)
                    ProviderCompat.moduleManager
                        .disable(module.id, opsCallback)
                },
                change = {
                    opsTasks.add(module.id)
                    ProviderCompat.moduleManager
                        .remove(module.id, opsCallback)
                }
            )

            State.DISABLE -> LocalUiState(
                alpha = 0.5f,
                toggle = {
                    opsTasks.add(module.id)
                    ProviderCompat.moduleManager
                        .enable(module.id, opsCallback)
                },
                change = {
                    opsTasks.add(module.id)
                    ProviderCompat.moduleManager
                        .remove(module.id, opsCallback)
                }
            )

            State.REMOVE -> LocalUiState(
                alpha = 0.5f,
                decoration = TextDecoration.LineThrough,
                change = {
                    if (!ProviderCompat.isKsu) {
                        opsTasks.add(module.id)
                        ProviderCompat.moduleManager
                            .enable(module.id, opsCallback)
                    }
                }
            )

            State.UPDATE -> LocalUiState()
        }

    @Composable
    fun rememberUiState(module: LocalModule): LocalUiState {
        return remember(key1 = module.state, key2 = isRefreshing) {
            createUiState(module)
        }
    }

    @Composable
    fun getVersionItem(module: LocalModule): VersionItem? {
        val item by remember {
            derivedStateOf {
                versionItemCache[module.id]
            }
        }

        LaunchedEffect(key1 = module) {
            if (!localRepository.hasUpdatableTag(module.id)) {
                versionItemCache.remove(module.id)
                return@LaunchedEffect
            }

            if (versionItemCache.containsKey(module.id)) return@LaunchedEffect

            val versionItem = if (module.updateJson.isNotBlank()) {
                UpdateJson.loadToVersionItem(module.updateJson)
            } else {
                localRepository.getVersionById(module.id)
                    .firstOrNull()
            }

            versionItemCache[module.id] = versionItem
        }

        return item
    }

    fun downloader(
        context: Context,
        module: LocalModule,
        item: VersionItem,
        onSuccess: (File) -> Unit
    ) {
        viewModelScope.launch {
            val downloadPath = userPreferencesRepository.data
                .first().downloadPath

            val filename = Utils.getFilename(
                name = module.name,
                version = item.version,
                versionCode = item.versionCode,
                extension = "zip"
            )

            val task = DownloadService.TaskItem(
                key = item.toString(),
                url = item.zipUrl,
                filename = filename,
                title = module.name,
                desc = item.versionDisplay
            )

            val listener = object : DownloadService.IDownloadListener {
                override fun getProgress(value: Float) {}
                override fun onSuccess() {
                    onSuccess(downloadPath.resolve(filename))
                }

                override fun onFailure(e: Throwable) {
                    Timber.d(e)
                }
            }

            DownloadService.start(
                context = context,
                task = task,
                listener = listener
            )
        }
    }

    @Composable
    fun getProgress(item: VersionItem?): Float {
        val progress by DownloadService.getProgressByKey(item.toString())
            .collectAsStateWithLifecycle(initialValue = 0f)

        return progress
    }

    @Stable
    data class LocalUiState(
        val alpha: Float = 1f,
        val decoration: TextDecoration = TextDecoration.None,
        val toggle: (Boolean) -> Unit = {},
        val change: () -> Unit = {}
    )
}