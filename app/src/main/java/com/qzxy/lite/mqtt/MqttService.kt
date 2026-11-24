package com.qzxy.lite.mqtt

import com.qzxy.lite.core.ApiConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MQTT 独立可选模块（AGENT-PROMPT 技术要求：断线重连、不阻塞 UI）。
 * Broker: tcp://mq.china-qzxy.cn:1883（wi/a.c Observed）。
 * TODO(probe)：topic 命名与鉴权方式未还原（notes/API.md §9），接入前不订阅任何业务 topic。
 */
class MqttService(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<Pair<String, MqttMessage>>(extraBufferCapacity = 64)
    val messages: SharedFlow<Pair<String, MqttMessage>> = _messages.asSharedFlow()

    private var client: MqttAsyncClient? = null
    private var broker: String = ApiConstants.MQTT_BROKER
    private var clientId: String = "qzxy-lite-${System.currentTimeMillis()}"
    private val running = AtomicBoolean(false)

    enum class State { IDLE, CONNECTING, CONNECTED, DISCONNECTED }

    fun start(brokerUrl: String = ApiConstants.MQTT_BROKER, clientTag: String = clientId) {
        if (!running.compareAndSet(false, true)) return
        broker = brokerUrl
        clientId = clientTag
        scope.launch { connectLoop() }
    }

    fun stop() {
        running.set(false)
        runCatching { client?.disconnect() }
        runCatching { client?.close() }
        client = null
        _state.value = State.IDLE
    }

    private suspend fun connectLoop() {
        var backoffMs = 2_000L
        while (running.get()) {
            try {
                _state.value = State.CONNECTING
                val c = MqttAsyncClient(broker, clientId, MemoryPersistence())
                client = c
                c.setCallback(object : MqttCallbackExtended {
                    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                        _state.value = State.CONNECTED
                        backoffMs = 2_000L
                        // TODO(probe): 订阅业务 topic（订单/设备状态推送），topic 与鉴权未还原前保持空。
                    }

                    override fun connectionLost(cause: Throwable?) {
                        _state.value = State.DISCONNECTED
                    }

                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        if (topic != null && message != null) _messages.tryEmit(topic to message)
                    }

                    override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
                })
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    isAutomaticReconnect = false // 重连交给 connectLoop 统一处理
                    connectionTimeout = 10
                    keepAliveInterval = 30
                    // TODO(probe): userName/password/ssl 配置待服务端鉴权方式确认。
                }
                c.connect(options).waitForCompletion(15_000)
                backoffMs = 2_000L
                // 连接保持由 Paho 心跳维护；此处等待断开信号。
                while (running.get() && c.isConnected) delay(1_000)
            } catch (t: Throwable) {
                _state.value = State.DISCONNECTED
            }
            if (running.get()) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }
}
