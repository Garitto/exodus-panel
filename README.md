# Exodus Panel (app Android para la OUPES Exodus 1500)

Controla tu OUPES por Bluetooth, con más ajustes que la app oficial Cleanergy:

- Datos en vivo: % de batería, temperatura, vatios de entrada (red/solar) y de salida.
- Interruptores de las salidas AC, DC y USB.
- Carga lenta / rápida (≈400 W / ≈775–800 W).
- Tiempo de pantalla.
- **Timeouts**: standby de la máquina, timeout de salida AC, timeout USB/12 V.
- **Modo silencioso** y luz frontal (breath).
- ECO AC/DC con umbrales (experimental).
- **Corte automático**: apaga las salidas que elijas al llegar a un % mínimo, y te avisa al llegar a un % máximo.
- Funciona en segundo plano (servicio con notificación).

El Exodus 1500 **no permite limitar la potencia de carga de forma continua** por Bluetooth (solo Fast/Slow). La app no lo promete.

## Cómo obtener el APK (se compila en GitHub, sin instalar nada en tu PC)

1. Crea una cuenta gratuita en https://github.com y pulsa **New repository**.
   Nombre: `exodus-panel`. Déjalo **Public** (el código no lleva ni tu MAC ni tu clave) y crea el repositorio.
2. En la página del repositorio vacío, pulsa **uploading an existing file**.
3. **Arrastra el contenido de esta carpeta** (`.github`, `app`, `build.gradle.kts`, `gradle.properties`,
   `settings.gradle.kts`, `.gitignore`, `README.md`), no el .zip. Pulsa **Commit changes**.
   - Comprueba que se subió `.github/workflows/build.yml`. Si falta: **Add file → Create new file**,
     nombre `.github/workflows/build.yml`, y pega el contenido del archivo.
4. Abre la pestaña **Actions**. Si pide habilitar los flujos, acepta. Se ejecuta sola ("Compilar APK");
   si no, entra en ella y pulsa **Run workflow**. La primera vez tarda unos 5-10 minutos.
5. Cuando salga el check verde, abre esa ejecución y baja hasta **Artifacts**: descarga **ExodusPanel-APK**
   (un .zip con `app-debug.apk` dentro).
6. Pasa el APK al teléfono e instálalo (hay que permitir "instalar apps de orígenes desconocidos";
   Play Protect puede avisar: elige "Instalar de todos modos").
7. Si la ejecución falla (X roja): abre la ejecución, entra en el paso "Compilar el APK", copia las líneas
   con `error:` y envíamelas.

## Primer uso

1. Cierra del todo la app oficial de OUPES (el equipo solo admite una conexión Bluetooth).
2. Abre Exodus Panel, escribe la MAC y la clave del dispositivo, pulsa **Conectar** y acepta los permisos.
3. En Android 11 o anterior, el escaneo exige tener la **ubicación del teléfono activada**.
4. Para que siga conectada con la pantalla apagada, quita las restricciones de batería a la app
   (botón dentro de la app) y, en Xiaomi/MIUI, activa su inicio automático.

## Cómo obtener MAC y clave

- **MAC**: en el equipo o en la app oficial (información del dispositivo). Formato `AA:BB:CC:DD:EE:FF`.
- **Clave (device_key)**: la que usa Cleanergy. Si ya tienes la integración de Home Assistant configurada, está en su configuración. También se puede extraer del tráfico de la app oficial (requiere captura).

## Pruebas recomendadas, en este orden

1. Conecta: deben aparecer batería, temperatura y vatios.
2. Apaga y enciende la salida DC desde la app.
3. Cambia a carga rápida y vuelve a lenta; comprueba los vatios de entrada.
4. Cambia el tiempo de pantalla y comprueba en el equipo.
5. Prueba **standby de la máquina**, **timeout AC** y **timeout USB/12V**.
6. Activa **modo silencioso** y comprueba el ruido de ventiladores.
7. Corte: activa el corte con un mínimo **por encima** del % actual y marca solo DC; debe apagarla en unos segundos.
   Desactiva el corte al terminar.
8. ECO: es experimental; comprueba cualquier cambio en la app oficial.

## Estructura

- `Protocol.kt`: paquetes del protocolo BLE (telemetría, Cmd2/Cmd3, CRC8).
- `OupesService.kt`: conexión Bluetooth, telemetría, escritura de ajustes y corte automático.
- `MainActivity.kt`: pantalla completa.
- `Config.kt`: ajustes guardados en el teléfono.

## Cambios en v1.1

- Timeouts de máquina, AC y USB/12V.
- Modo silencioso y luz frontal.
- Consulta ampliada de ajustes al conectar.
- UI más clara y notas de uso.
