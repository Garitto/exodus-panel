package com.garitto.exoduspanel

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Mantiene la conexión Bluetooth con la OUPES en segundo plano (servicio en primer plano),
 * guarda la telemetría y ejecuta el corte automático por mínimo.
 */
class OupesService : Service() {

    companion object {
        const val ACTION_START = "com.garitto.exoduspanel.START"
        const val ACTION_STOP = "com.garitto.exoduspanel.STOP"
        private const val CH_STATUS = "estado"
        private const val CH_ALERT = "avisos"
        private const val NOTIF_ID = 1
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        @Volatile var instance: OupesService? = null
        @Volatile var status: String = "Desconectado"
        @Volatile var ready: Boolean = false
        @Volatile var lastRx: Long = 0L
        val attrs = ConcurrentHashMap<Int, Int>()

        private val logLines = ArrayDeque<String>()
        private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun log(msg: String) {
            synchronized(logLines) {
                logLines.addLast(fmt.format(Date()) + "  " + msg)
                while (logLines.size > 40) logLines.removeFirst()
            }
        }

        fun logText(): String = synchronized(logLines) { logLines.reversed().take(14).joinToString("\n") }

        fun telemetryFresh(): Boolean = SystemClock.elapsedRealtime() - lastRx < 15000
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var cfg: Config
    private var adapter: BluetoothAdapter? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var running = false
    private var scanning = false
    private val queue = ArrayDeque<ByteArray>()
    private var pumping = false
    private var retryPending = false

    // Estado del corte automático
    private var lowHits = 0
    private var lowActive = false
    private var maxActive = false
    private var lastCutTry = 0L
    private var turnedOff = 0

    // ------------------------------------------------------------------ ciclo de vida
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        cfg = Config(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown("Detenido")
            return START_NOT_STICKY
        }
        try {
            goForeground("Iniciando…")
        } catch (e: Exception) {
            log("Android no permitió iniciar el servicio: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) {
            // Se pulsó Conectar otra vez (p. ej. cambió la MAC o la clave): reconectar desde cero
            stopScan()
            closeGatt()
            ready = false
            retryPending = false
            beginScan()
        } else {
            running = true
            val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            adapter = mgr.adapter
            log("Servicio iniciado")
            main.postDelayed(tick, 1000)
            main.postDelayed(keepaliveTask, 6000)
            main.postDelayed(refreshTask, 8000)
            beginScan()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()
        ready = false
        instance = null
        super.onDestroy()
    }

    private fun shutdown(reason: String) {
        running = false
        cfg.wantConnected = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        closeGatt()
        ready = false
        setStatus(reason)
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        stopSelf()
    }

    // ------------------------------------------------------------------ notificaciones
    private fun createChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Estado de la conexión", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Avisos de batería", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun buildNotification(text: String): Notification =
        Notification.Builder(this, CH_STATUS)
            .setContentTitle("Exodus Panel")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()

    private fun goForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun setStatus(s: String) {
        status = s
        log(s)
        if (running) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(s))
        }
    }

    private fun alert(id: Int, title: String, text: String) {
        log("AVISO: $title - $text")
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = Notification.Builder(this, CH_ALERT)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }

    // ------------------------------------------------------------------ escaneo y conexión
    @SuppressLint("MissingPermission")
    private fun beginScan() {
        retryPending = false
        if (!running || scanning) return
        val mac = cfg.mac
        if (!BluetoothAdapter.checkBluetoothAddress(mac) || cfg.key.length < 4) {
            setStatus("Falta la MAC o la clave: complétalas y pulsa Conectar")
            shutdown("Configuración incompleta")
            return
        }
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            setStatus("Bluetooth apagado; reintento en 10 s")
            main.postDelayed({ beginScan() }, 10000)
            return
        }
        val scanner = bt.bluetoothLeScanner
        if (scanner == null) {
            main.postDelayed({ beginScan() }, 5000)
            return
        }
        setStatus("Buscando $mac …")
        try {
            val filter = ScanFilter.Builder().setDeviceAddress(mac).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanning = true
            scanner.startScan(listOf(filter), settings, scanCb)
            main.postDelayed(scanTimeout, 15000)
        } catch (e: Exception) {
            scanning = false
            setStatus("Error al buscar: ${e.message}")
            main.postDelayed({ beginScan() }, 8000)
        }
    }

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            setStatus("No encuentro el equipo (¿encendido y con Bluetooth activo?). Reintento…")
            main.postDelayed({ beginScan() }, 3000)
        }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post {
                if (!scanning) return@post
                stopScan()
                connect(result.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                stopScan()
                setStatus("Falló el escaneo (código $errorCode). Reintento…")
                main.postDelayed({ beginScan() }, 8000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCb)
        } catch (_: Exception) {
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        setStatus("Conectando…")
        closeGatt()
        gatt = device.connectGatt(this, false, gattCb, BluetoothDevice.TRANSPORT_LE)
        main.postDelayed(connectTimeout, 30000)
    }

    private val connectTimeout = Runnable {
        if (!ready) {
            setStatus("Se agotó el tiempo de conexión. Reintento…")
            onLost()
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        main.removeCallbacks(connectTimeout)
        writeChar = null
        queue.clear()
        pumping = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
    }

    private fun onLost() {
        ready = false
        closeGatt()
        if (running && !retryPending) {
            retryPending = true
            setStatus("Desconectado; reintento en 5 s")
            main.postDelayed({ beginScan() }, 5000)
        }
    }

    @SuppressLint("MissingPermission")
    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // El equipo necesita ~1,8 s tras conectar antes de hablarle
                main.postDelayed({
                    if (gatt === g) {
                        try { g.discoverServices() } catch (_: Exception) { onLost() }
                    }
                }, 1800)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                main.post { if (gatt === g || gatt == null) onLost() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post { if (gatt === g) setupServices(g) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            main.post { if (gatt === g) startSession() }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            val parsed = Protocol.parse(v.copyOf())
            if (parsed.isNotEmpty()) {
                attrs.putAll(parsed)
                lastRx = SystemClock.elapsedRealtime()
            }
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun setupServices(g: BluetoothGatt) {
        var notify: BluetoothGattCharacteristic? = null
        var write: BluetoothGattCharacteristic? = null
        val nu = UUID.fromString(Protocol.NOTIFY_UUID)
        val wu = UUID.fromString(Protocol.WRITE_UUID)
        for (s in g.services) for (c in s.characteristics) {
            if (c.uuid == nu) notify = c
            if (c.uuid == wu) write = c
        }
        if (notify == null || write == null) {
            setStatus("El equipo no tiene los canales esperados (¿es una Exodus?)")
            onLost()
            return
        }
        writeChar = write
        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD)
        if (cccd == null) {
            startSession()
            return
        }
        val indicate = (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0 &&
            (notify.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        cccd.value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        g.writeDescriptor(cccd)
    }

    private fun startSession() {
        if (ready) return
        main.removeCallbacks(connectTimeout)
        main.postDelayed({
            if (!running || gatt == null) return@postDelayed
            for (p in Protocol.initSequence(cfg.key)) enqueue(p)
            ready = true
            lowHits = 0
            setStatus("Conectado")
            main.postDelayed({ refreshSettings() }, 1500)
        }, 200)
    }

    // ------------------------------------------------------------------ escritura
    private fun enqueue(p: ByteArray) {
        queue.add(p)
        if (!pumping) {
            pumping = true
            main.postDelayed(pump, 5)
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private val pump = object : Runnable {
        override fun run() {
            val g = gatt
            val c = writeChar
            val p = queue.poll()
            if (p == null || g == null || c == null) {
                pumping = false
                return
            }
            try {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = p
                g.writeCharacteristic(c)
            } catch (e: Exception) {
                log("Error al escribir: ${e.message}")
            }
            main.postDelayed(this, 30)
        }
    }

    private val keepaliveTask = object : Runnable {
        override fun run() {
            if (ready) enqueue(Protocol.KEEPALIVE)
            if (running) main.postDelayed(this, 10000)
        }
    }

    private val refreshTask = object : Runnable {
        override fun run() {
            if (ready) refreshSettings()
            if (running) main.postDelayed(this, 20000)
        }
    }

    // ------------------------------------------------------------------ API para la pantalla
    /** Pide al equipo los valores de los ajustes (en grupos de ≤7 DPIDs). */
    fun refreshSettings() {
        if (!ready) return
        // Carga + pantalla + ECO
        enqueue(Protocol.queryCommand(listOf(41, 105, 110, 111, 112, 113)))
        // Timeouts y modos
        enqueue(Protocol.queryCommand(listOf(45, 47, 49, 58, 63)))
    }

    fun setOutput(bit: Int, on: Boolean) {
        val cur = attrs[Protocol.ATTR_OUTPUTS] ?: return
        val next = if (on) cur or bit else cur and bit.inv()
        setOutputsMask(next)
    }

    private fun setOutputsMask(mask: Int) {
        if (!ready) return
        log("Salidas -> 0x%02X".format(mask))
        enqueue(Protocol.outputCommand(mask))
    }

    fun writeSetting(dpid: Int, value: Int) {
        if (!ready) {
            log("No conectado: no se escribió el ajuste $dpid")
            return
        }
        log("Ajuste $dpid = $value")
        enqueue(Protocol.settingCommand(dpid, value))
        main.postDelayed({ refreshSettings() }, 1500)
    }

    // ------------------------------------------------------------------ corte automático
    private val tick = object : Runnable {
        override fun run() {
            try {
                guardStep()
            } catch (e: Exception) {
                log("Error en el vigilante: ${e.message}")
            }
            if (running) main.postDelayed(this, 1000)
        }
    }

    private fun guardStep() {
        if (!ready || !telemetryFresh()) return
        val soc = attrs[Protocol.ATTR_SOC] ?: return
        val outs = attrs[Protocol.ATTR_OUTPUTS] ?: return
        val now = SystemClock.elapsedRealtime()

        if (cfg.cutEnabled) {
            lowHits = if (soc <= cfg.minPct) lowHits + 1 else 0
            if (lowHits >= 5 && !lowActive) {
                lowActive = true
                alert(2, "Batería en el mínimo", "$soc% (mínimo ${cfg.minPct}%): apagando salidas")
            }
            if (lowActive) {
                if (soc >= cfg.minPct + 5) {
                    lowActive = false
                    if (cfg.resume && turnedOff != 0) {
                        setOutputsMask(outs or turnedOff)
                        turnedOff = 0
                    }
                } else if (now - lastCutTry >= 30000) {
                    lastCutTry = now
                    val toOff = outs and cfg.cutMask
                    if (toOff != 0) {
                        turnedOff = turnedOff or toOff
                        setOutputsMask(outs and cfg.cutMask.inv())
                    }
                }
            }
        } else {
            lowHits = 0
            lowActive = false
        }

        val maxPct = cfg.maxAlert
        if (maxPct > 0) {
            if (soc >= maxPct && !maxActive) {
                maxActive = true
                alert(3, "Batería al $soc%", "Llegó a tu aviso de $maxPct%: desconecta el cargador")
            } else if (maxActive && soc <= maxPct - 5) {
                maxActive = false
            }
        }
    }
}
