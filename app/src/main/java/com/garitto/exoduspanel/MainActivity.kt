package com.garitto.exoduspanel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var cfg: Config
    private val ui = Handler(Looper.getMainLooper())
    private var pendingConnect = false

    // Conexión
    private lateinit var macEdit: EditText
    private lateinit var keyEdit: EditText
    private lateinit var statusView: TextView

    // Datos
    private lateinit var socView: TextView
    private lateinit var powerView: TextView

    // Control
    private lateinit var swAc: Switch
    private lateinit var swDc: Switch
    private lateinit var swUsb: Switch
    private lateinit var swFast: Switch
    private lateinit var screenSpinner: Spinner
    private lateinit var screenNow: TextView
    private lateinit var machineSpinner: Spinner
    private lateinit var machineNow: TextView
    private lateinit var acStandbySpinner: Spinner
    private lateinit var acStandbyNow: TextView
    private lateinit var usbStandbySpinner: Spinner
    private lateinit var usbStandbyNow: TextView
    private lateinit var swSilent: Switch
    private lateinit var swBreath: Switch
    private lateinit var swAcEco: Switch
    private lateinit var swDcEco: Switch
    private lateinit var acEcoW: EditText
    private lateinit var dcEcoW: EditText

    // Corte
    private lateinit var swCut: Switch
    private lateinit var minEdit: EditText
    private lateinit var cbAc: CheckBox
    private lateinit var cbDc: CheckBox
    private lateinit var cbUsb: CheckBox
    private lateinit var swResume: Switch
    private lateinit var maxEdit: EditText

    private lateinit var logView: TextView

    private val screenOptions = listOf(
        10 to "10 s", 20 to "20 s", 30 to "30 s", 60 to "1 min",
        300 to "5 min", 1800 to "30 min"
    )

    private val timeoutOptions = listOf(
        0 to "Nunca",
        300 to "5 min",
        600 to "10 min",
        1800 to "30 min",
        3600 to "1 h",
        7200 to "2 h",
        21600 to "6 h"
    )

    // ------------------------------------------------------------------ utilidades de interfaz
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 8) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).also { it.topMargin = dp(top) }

    private fun LinearLayout.addV(v: View, top: Int = 8): View {
        addView(v, lp(top))
        return v
    }

    private fun header(root: LinearLayout, t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 18f
        v.setTypeface(null, Typeface.BOLD)
        v.setTextColor(0xFF4DD0E1.toInt())
        root.addV(v, 22)
    }

    private fun note(root: LinearLayout, t: String) {
        val v = TextView(this)
        v.text = t
        v.textSize = 12f
        v.alpha = 0.75f
        root.addV(v, 4)
    }

    private fun button(root: LinearLayout, t: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.setOnClickListener { onClick() }
        root.addV(b, 8)
        return b
    }

    private fun edit(root: LinearLayout, hint: String, value: String, numeric: Boolean): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.setSingleLine(true)
        if (numeric) e.inputType = InputType.TYPE_CLASS_NUMBER
        root.addV(e, 4)
        return e
    }

    private fun sw(root: LinearLayout, t: String, onUser: (Boolean) -> Unit): Switch {
        val s = Switch(this)
        s.text = t
        s.setOnCheckedChangeListener { v, checked -> if (v.isPressed) onUser(checked) }
        root.addV(s, 6)
        return s
    }

    private fun spinnerOf(options: List<Pair<Int, String>>): Spinner {
        val s = Spinner(this)
        s.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options.map { it.second })
        return s
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun service() = OupesService.instance?.takeIf { OupesService.ready }

    // ------------------------------------------------------------------ construcción de la pantalla
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Config(this)

        val scroll = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(16), dp(16), dp(32))
        scroll.addView(root)
        setContentView(scroll)

        val title = TextView(this)
        title.text = "Exodus Panel"
        title.textSize = 26f
        title.setTypeface(null, Typeface.BOLD)
        root.addV(title, 4)

        // --- Conexión ---
        header(root, "Conexión")
        macEdit = edit(root, "MAC del equipo (A0:85:E3:…)", cfg.mac, false)
        keyEdit = edit(root, "Clave del dispositivo (10 caracteres)", cfg.key, false)
        statusView = TextView(this)
        root.addV(statusView, 8)
        button(root, "Conectar") { onConnectClicked() }
        button(root, "Detener") {
            cfg.wantConnected = false
            startService(Intent(this, OupesService::class.java).setAction(OupesService.ACTION_STOP))
        }
        button(root, "Ajustes de batería del teléfono") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        note(root, "Cierra la app oficial de OUPES antes de conectar (solo admite una conexión BLE). " +
            "Para que siga con la pantalla apagada: quita las restricciones de batería a esta app " +
            "y, en Xiaomi/MIUI, actívale el inicio automático.")

        // --- Datos en vivo ---
        header(root, "En vivo")
        socView = TextView(this)
        socView.textSize = 22f
        root.addV(socView, 6)
        powerView = TextView(this)
        powerView.textSize = 15f
        root.addV(powerView, 4)

        // --- Salidas ---
        header(root, "Salidas")
        swAc = sw(root, "AC (enchufes)") { service()?.setOutput(Protocol.OUT_AC, it) ?: toast("No conectado") }
        swDc = sw(root, "DC 12 V") { service()?.setOutput(Protocol.OUT_DC, it) ?: toast("No conectado") }
        swUsb = sw(root, "USB") { service()?.setOutput(Protocol.OUT_USB, it) ?: toast("No conectado") }

        // --- Carga ---
        header(root, "Carga")
        swFast = sw(root, "Carga rápida") { fast ->
            service()?.writeSetting(Protocol.DPID_CHARGE_MODE, if (fast) 1 else 0) ?: toast("No conectado")
        }
        note(root, "Lenta ≈ 400 W · Rápida ≈ 775–800 W. El Exodus 1500 no permite limitar la potencia " +
            "de carga de forma continua por Bluetooth (solo estos dos modos).")

        // --- Pantalla y timeouts ---
        header(root, "Pantalla y timeouts")
        screenNow = TextView(this)
        root.addV(screenNow, 4)
        screenSpinner = spinnerOf(screenOptions)
        root.addV(screenSpinner, 4)
        button(root, "Aplicar tiempo de pantalla") {
            val secs = screenOptions[screenSpinner.selectedItemPosition].first
            service()?.writeSetting(Protocol.DPID_SCREEN, secs) ?: toast("No conectado")
        }

        machineNow = TextView(this)
        root.addV(machineNow, 10)
        machineSpinner = spinnerOf(timeoutOptions)
        root.addV(machineSpinner, 4)
        button(root, "Aplicar standby de la máquina") {
            val secs = timeoutOptions[machineSpinner.selectedItemPosition].first
            service()?.writeSetting(Protocol.DPID_MACHINE_STANDBY, secs) ?: toast("No conectado")
        }
        note(root, "Standby de la máquina: apaga todo el equipo tras ese tiempo sin actividad (0 = nunca).")

        acStandbyNow = TextView(this)
        root.addV(acStandbyNow, 10)
        acStandbySpinner = spinnerOf(timeoutOptions)
        root.addV(acStandbySpinner, 4)
        button(root, "Aplicar timeout salida AC") {
            val secs = timeoutOptions[acStandbySpinner.selectedItemPosition].first
            service()?.writeSetting(Protocol.DPID_AC_STANDBY, secs) ?: toast("No conectado")
        }

        usbStandbyNow = TextView(this)
        root.addV(usbStandbyNow, 10)
        usbStandbySpinner = spinnerOf(timeoutOptions)
        root.addV(usbStandbySpinner, 4)
        button(root, "Aplicar timeout USB / 12 V") {
            val secs = timeoutOptions[usbStandbySpinner.selectedItemPosition].first
            service()?.writeSetting(Protocol.DPID_USB_STANDBY, secs) ?: toast("No conectado")
        }

        // --- Modos ---
        header(root, "Modos")
        swSilent = sw(root, "Modo silencioso (ventiladores)") { on ->
            service()?.writeSetting(Protocol.DPID_SILENT, if (on) 1 else 0) ?: toast("No conectado")
        }
        swBreath = sw(root, "Luz frontal (breath)") { on ->
            service()?.writeSetting(Protocol.DPID_BREATH, if (on) 1 else 0) ?: toast("No conectado")
        }

        // --- ECO ---
        header(root, "ECO (experimental)")
        note(root, "Apaga la salida cuando su consumo queda por debajo del umbral. " +
            "El tiempo de espera del ECO se cambia en la app oficial. Umbral 0 W ≈ casi nunca actúa.")
        swAcEco = sw(root, "AC-ECO") { service()?.writeSetting(Protocol.DPID_AC_ECO, if (it) 1 else 0) ?: toast("No conectado") }
        acEcoW = edit(root, "Umbral AC-ECO (W, 0-100)", "", true)
        swDcEco = sw(root, "DC-ECO") { service()?.writeSetting(Protocol.DPID_DC_ECO, if (it) 1 else 0) ?: toast("No conectado") }
        dcEcoW = edit(root, "Umbral DC-ECO (W, 0-100)", "", true)
        button(root, "Aplicar umbrales ECO") {
            val a = acEcoW.text.toString().toIntOrNull()
            val d = dcEcoW.text.toString().toIntOrNull()
            val s = service()
            if (s == null) {
                toast("No conectado")
            } else {
                if (a != null && a in 0..100) s.writeSetting(Protocol.DPID_AC_ECO_W, a)
                if (d != null && d in 0..100) s.writeSetting(Protocol.DPID_DC_ECO_W, d)
                if ((a != null && a !in 0..100) || (d != null && d !in 0..100)) toast("Los umbrales van de 0 a 100 W")
            }
        }

        // --- Corte por mínimo ---
        header(root, "Corte automático por batería baja")
        swCut = Switch(this)
        swCut.text = "Activar corte"
        swCut.isChecked = cfg.cutEnabled
        root.addV(swCut, 6)
        minEdit = edit(root, "Apagar al llegar a (%)", cfg.minPct.toString(), true)
        cbAc = CheckBox(this); cbAc.text = "Apagar AC"; cbAc.isChecked = cfg.cutMask and Protocol.OUT_AC != 0
        cbDc = CheckBox(this); cbDc.text = "Apagar DC"; cbDc.isChecked = cfg.cutMask and Protocol.OUT_DC != 0
        cbUsb = CheckBox(this); cbUsb.text = "Apagar USB"; cbUsb.isChecked = cfg.cutMask and Protocol.OUT_USB != 0
        root.addV(cbAc, 2); root.addV(cbDc, 0); root.addV(cbUsb, 0)
        swResume = Switch(this)
        swResume.text = "Reactivar las salidas al subir 5 % sobre el mínimo"
        swResume.isChecked = cfg.resume
        root.addV(swResume, 6)
        maxEdit = edit(root, "Avisarme al llegar a (%, 0 = no)", cfg.maxAlert.toString(), true)
        button(root, "Guardar corte y avisos") { saveCut() }
        note(root, "El corte solo funciona mientras la app esté conectada. Si enciendes una salida a mano " +
            "estando bajo el mínimo, se vuelve a apagar en unos 30 s.")

        // --- Registro ---
        header(root, "Registro")
        logView = TextView(this)
        logView.textSize = 11f
        logView.setTypeface(Typeface.MONOSPACE)
        root.addV(logView, 4)

        if (cfg.wantConnected && OupesService.instance == null && missingPermissions().isEmpty()) startService()
    }

    // ------------------------------------------------------------------ permisos y arranque
    private fun missingPermissions(): List<String> {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            need += Manifest.permission.BLUETOOTH_CONNECT
            need += Manifest.permission.BLUETOOTH_SCAN
        } else {
            need += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        return need.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private fun onConnectClicked() {
        val mac = macEdit.text.toString().trim().uppercase()
        val key = keyEdit.text.toString().trim()
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(mac)) {
            toast("La MAC no es válida (formato AA:BB:CC:DD:EE:FF)")
            return
        }
        if (key.length < 4) {
            toast("Falta la clave del dispositivo")
            return
        }
        cfg.mac = mac
        cfg.key = key
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            pendingConnect = true
            requestPermissions(missing.toTypedArray(), 100)
            return
        }
        startService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 100 || !pendingConnect) return
        pendingConnect = false
        val btOk = permissions.indices.none { i ->
            permissions[i] != Manifest.permission.POST_NOTIFICATIONS && grantResults[i] != PackageManager.PERMISSION_GRANTED
        }
        if (btOk) startService() else toast("Sin permiso de Bluetooth no puedo conectar")
    }

    private fun startService() {
        cfg.wantConnected = true
        startForegroundService(Intent(this, OupesService::class.java).setAction(OupesService.ACTION_START))
    }

    private fun saveCut() {
        val min = minEdit.text.toString().toIntOrNull()
        val max = maxEdit.text.toString().toIntOrNull() ?: 0
        if (min == null || min !in 0..99) {
            toast("El mínimo va de 0 a 99 %")
            return
        }
        if (max !in 0..100) {
            toast("El aviso va de 0 a 100 %")
            return
        }
        var mask = 0
        if (cbAc.isChecked) mask = mask or Protocol.OUT_AC
        if (cbDc.isChecked) mask = mask or Protocol.OUT_DC
        if (cbUsb.isChecked) mask = mask or Protocol.OUT_USB
        if (swCut.isChecked && mask == 0) {
            toast("Marca al menos una salida para apagar")
            return
        }
        cfg.cutEnabled = swCut.isChecked
        cfg.minPct = min
        cfg.cutMask = mask
        cfg.resume = swResume.isChecked
        cfg.maxAlert = max
        toast("Guardado")
    }

    // ------------------------------------------------------------------ refresco
    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    private fun setIfIdle(e: EditText, value: Int?) {
        if (value != null && !e.hasFocus()) {
            val t = value.toString()
            if (e.text.toString() != t) e.setText(t)
        }
    }

    private fun selectClosest(spinner: Spinner, options: List<Pair<Int, String>>, value: Int?) {
        if (value == null) return
        val idx = options.indexOfFirst { it.first == value }.let { if (it >= 0) it else -1 }
        if (idx >= 0 && spinner.selectedItemPosition != idx) spinner.setSelection(idx)
    }

    private fun render() {
        val a = OupesService.attrs
        val live = OupesService.ready && OupesService.telemetryFresh()
        statusView.text = "Estado: " + OupesService.status + if (live) "  ·  datos en vivo" else ""

        if (live) {
            val soc = a[Protocol.ATTR_SOC]
            val t = a[Protocol.ATTR_TEMP]?.let { "%.0f °C".format(Protocol.tempC(it)) } ?: "--"
            socView.text = "Batería: ${soc ?: "--"} %    Temp: $t"
            powerView.text = "Entrada: ${a[Protocol.ATTR_IN_W] ?: "--"} W (red ${a[Protocol.ATTR_GRID_W] ?: "--"}, " +
                "solar ${a[Protocol.ATTR_SOLAR_W] ?: "--"})\nSalida: ${a[Protocol.ATTR_OUT_W] ?: "--"} W"

            val outs = a[Protocol.ATTR_OUTPUTS]
            if (outs != null) {
                swAc.isChecked = outs and Protocol.OUT_AC != 0
                swDc.isChecked = outs and Protocol.OUT_DC != 0
                swUsb.isChecked = outs and Protocol.OUT_USB != 0
            }

            a[Protocol.DPID_CHARGE_MODE]?.let { swFast.isChecked = it == 1 }
            a[Protocol.DPID_SILENT]?.let { swSilent.isChecked = it == 1 }
            a[Protocol.DPID_BREATH]?.let { swBreath.isChecked = it == 1 }

            a[Protocol.DPID_SCREEN]?.let { secs ->
                screenNow.text = "Pantalla ahora: ${Protocol.formatSeconds(secs)}"
                selectClosest(screenSpinner, screenOptions, secs)
            }
            a[Protocol.DPID_MACHINE_STANDBY]?.let { secs ->
                machineNow.text = "Standby máquina ahora: ${Protocol.formatSeconds(secs)}"
                selectClosest(machineSpinner, timeoutOptions, secs)
            }
            a[Protocol.DPID_AC_STANDBY]?.let { secs ->
                acStandbyNow.text = "Timeout AC ahora: ${Protocol.formatSeconds(secs)}"
                selectClosest(acStandbySpinner, timeoutOptions, secs)
            }
            a[Protocol.DPID_USB_STANDBY]?.let { secs ->
                usbStandbyNow.text = "Timeout USB/12V ahora: ${Protocol.formatSeconds(secs)}"
                selectClosest(usbStandbySpinner, timeoutOptions, secs)
            }

            a[Protocol.DPID_AC_ECO]?.let { swAcEco.isChecked = it == 1 }
            a[Protocol.DPID_DC_ECO]?.let { swDcEco.isChecked = it == 1 }
            setIfIdle(acEcoW, a[Protocol.DPID_AC_ECO_W])
            setIfIdle(dcEcoW, a[Protocol.DPID_DC_ECO_W])
        } else {
            socView.text = "Batería: --"
            powerView.text = "Sin datos en vivo"
        }
        logView.text = OupesService.logText()
    }
}
