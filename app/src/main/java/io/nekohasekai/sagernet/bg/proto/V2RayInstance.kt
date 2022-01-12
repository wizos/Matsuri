/******************************************************************************
 *                                                                            *
 * Copyright (C) 2021 by nekohasekai <contact-sagernet@sekai.icu>             *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg.proto

import android.annotation.SuppressLint
import android.os.Build
import android.os.SystemClock
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.TrojanProvider
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.V2rayBuildResult
import io.nekohasekai.sagernet.fmt.buildV2RayConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteriaConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.pingtunnel.PingTunnelBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan.buildTrojanConfig
import io.nekohasekai.sagernet.fmt.trojan.buildTrojanGoConfig
import io.nekohasekai.sagernet.fmt.trojan_go.TrojanGoBean
import io.nekohasekai.sagernet.fmt.trojan_go.buildTrojanGoConfig
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.*
import libcore.Libcore
import libcore.V2RayInstance
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

abstract class V2RayInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: V2rayBuildResult
    lateinit var v2rayPoint: V2RayInstance
    private lateinit var wsForwarder: WebView

    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, Pair<Int, String>>()
    val externalInstances = hashMapOf<Int, AbstractInstance>()
    open lateinit var processes: GuardedProcessPool
    private var cacheFiles = ArrayList<File>()
    var closed by AtomicBoolean()
    fun isInitialized(): Boolean {
        return ::config.isInitialized
    }

    protected fun initPlugin(name: String): PluginManager.InitResult {
        return pluginPath.getOrPut(name) { PluginManager.init(name)!! }
    }

    protected open fun buildConfig() {
        config = buildV2RayConfig(profile)
    }

    protected open fun loadConfig() {
        Libcore.setEnableLog(DataStore.enableLog)
        v2rayPoint.loadConfig(config.config)
    }

    open fun init() {
        v2rayPoint = V2RayInstance()
        buildConfig()
        val enableMux = DataStore.enableMux
        for ((chain) in config.index) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                val needChain = index != chain.size - 1
                val needMux = enableMux && (index == chain.size - 1)

                when (val bean = profile.requireBean()) {
                    is TrojanBean -> {
                        when (DataStore.providerTrojan) {
                            TrojanProvider.TROJAN -> {
                                initPlugin("trojan-plugin")
                                pluginConfigs[port] = profile.type to bean.buildTrojanConfig(
                                    port
                                )
                            }
                            TrojanProvider.TROJAN_GO -> {
                                initPlugin("trojan-go-plugin")
                                pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(
                                    port, needMux
                                )
                            }
                        }
                    }
                    is TrojanGoBean -> {
                        initPlugin("trojan-go-plugin")
                        pluginConfigs[port] = profile.type to bean.buildTrojanGoConfig(
                            port, needMux
                        )
                    }
                    is NaiveBean -> {
                        initPlugin("naive-plugin")
                        pluginConfigs[port] = profile.type to bean.buildNaiveConfig(port)
                    }
                    is PingTunnelBean -> {
                        if (needChain) error("PingTunnel is incompatible with chain")
                        initPlugin("pingtunnel-plugin")
                    }
                    is HysteriaBean -> {
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = profile.type to bean.buildHysteriaConfig(port) {
                            File(
                                app.cacheDir,
                                "hysteria_" + SystemClock.elapsedRealtime() + ".ca"
                            ).apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }
                }
            }
        }
        loadConfig()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun launch() {
        val context = if (Build.VERSION.SDK_INT < 24 || SagerNet.user.isUserUnlocked) SagerNet.application else SagerNet.deviceStorage
        val cache = File(context.cacheDir,"tmpcfg")
        cache.mkdirs()

        for ((chain) in config.index) {
            chain.entries.forEachIndexed { index, (port, profile) ->
                val bean = profile.requireBean()
                val needChain = index != chain.size - 1
                val (profileType, config) = pluginConfigs[port] ?: 0 to ""

                when {
                    externalInstances.containsKey(port) -> {
                        externalInstances[port]!!.launch()
                    }
                    bean is TrojanBean -> {
                        val configFile = File(
                            cache,
                            "trojan_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = listOf(
                            when (DataStore.providerTrojan) {
                                TrojanProvider.TROJAN -> initPlugin("trojan-plugin")
                                else -> initPlugin("trojan-go-plugin")
                            }.path, "--config", configFile.absolutePath
                        )

                        processes.start(commands)
                    }
                    bean is TrojanGoBean -> {
                        val configFile = File(
                            cache,
                            "trojan_go_" + SystemClock.elapsedRealtime() + ".json"
                        )
                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("trojan-go-plugin").path, "-config", configFile.absolutePath
                        )

                        processes.start(commands)
                    }
                    bean is NaiveBean -> {
                        val configFile = File(
                            cache,
                            "naive_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val envMap = mutableMapOf<String, String>()

                        if (bean.certificates.isNotBlank()) {
                            val certFile = File(
                                cache,
                                "naive_" + SystemClock.elapsedRealtime() + ".crt"
                            )

                            certFile.parentFile?.mkdirs()
                            certFile.writeText(bean.certificates)
                            cacheFiles.add(certFile)

                            envMap["SSL_CERT_FILE"] = certFile.absolutePath
                        }

                        val commands = mutableListOf(
                            initPlugin("naive-plugin").path, configFile.absolutePath
                        )

                        processes.start(commands, envMap)
                    }
                    bean is PingTunnelBean -> {
                        if (needChain) error("PingTunnel is incompatible with chain")

                        val commands = mutableListOf(
                            "su",
                            "-c",
                            initPlugin("pingtunnel-plugin").path,
                            "-type",
                            "client",
                            "-sock5",
                            "1",
                            "-l",
                            "$LOCALHOST:$port",
                            "-s",
                            bean.serverAddress
                        )

                        if (bean.key.isNotBlank() && bean.key != "1") {
                            commands.add("-key")
                            commands.add(bean.key)
                        }

                        processes.start(commands)
                    }
                    bean is HysteriaBean -> {
                        val configFile = File(
                            cache,
                            "hysteria_" + SystemClock.elapsedRealtime() + ".json"
                        )

                        configFile.parentFile?.mkdirs()
                        configFile.writeText(config)
                        cacheFiles.add(configFile)

                        val commands = mutableListOf(
                            initPlugin("hysteria-plugin").path,
                            "--no-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.enableLog) "trace" else "warn",
                            "client"
                        )

                        if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) {
                            commands.addAll(0, listOf("su", "-c"))
                        }

                        processes.start(commands)
                    }
                }
            }
        }

        v2rayPoint.start()

        if (config.requireWs) {
            val url = "http://$LOCALHOST:" + (config.wsPort) + "/"

            runOnMainDispatcher {
                wsForwarder = WebView(context)
                wsForwarder.settings.javaScriptEnabled = true
                wsForwarder.webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?,
                    ) {
                        Logs.d("WebView load r: $error")

                        runOnMainDispatcher {
                            wsForwarder.loadUrl("about:blank")

                            delay(1000L)
                            wsForwarder.loadUrl(url)
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        super.onPageFinished(view, url)

                        Logs.d("WebView loaded: ${view.title}")

                    }
                }
                wsForwarder.loadUrl(url)
            }
        }

    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        for (instance in externalInstances.values) {
            runCatching {
                instance.close()
            }
        }

        cacheFiles.removeAll { it.delete(); true }

        if (::wsForwarder.isInitialized) {
            runBlocking {
                onMainDispatcher {
                    wsForwarder.loadUrl("about:blank")
                    wsForwarder.destroy()
                }
            }
        }

        if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)

        if (::v2rayPoint.isInitialized) {
            v2rayPoint.close()
        }
    }

}