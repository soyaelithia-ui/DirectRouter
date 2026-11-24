# DirectRouter

**DirectRouter** es una aplicación Android **sin necesidad de root** que convierte tu teléfono en un **Router Wi-Fi Direct** con un **proxy local híbrido (HTTP/HTTPS + SOCKS5 TCP/UDP)** escuchando en un solo puerto (`192.168.49.1:8080`), además de incluir un **modo secundario Cliente VPN** basado en `hev-socks5-tunnel` para tunelizar todo el tráfico (TCP, UDP y DNS) desde un segundo dispositivo Android.

---

## Descarga e Instalación

Descarga el APK listo para instalar desde la última versión publicada en GitHub Releases:

👉 **[Descargar última versión (APK) — GitHub Releases](https://github.com/soyaelithia-ui/DirectRouter/releases/latest)**

### Pasos para instalar el APK en Android
1. Abre el enlace anterior desde tu teléfono Android y descarga el archivo `DirectRouter-v1.0.0-debug.apk` en la sección **Assets**.
2. Toca el archivo descargado desde tus notificaciones o desde el gestor de archivos (**Descargas**).
3. Si Android lo solicita, habilita **Instalar aplicaciones desconocidas** (*Ajustes → Seguridad / Aplicaciones → Instalar apps desconocidas* para tu navegador o gestor de archivos).
4. Confirma tocando **Instalar** y luego abre **DirectRouter**.

---

## Guías de Uso

### A) Uso prioritario: 1 solo teléfono como Router + cliente sin app

Ideal para compartir internet a **otro teléfono (Android/iOS), PC, Mac, consola o tablet** sin instalar nada en el dispositivo cliente.

1. En el teléfono que tiene internet (datos móviles o Wi-Fi), abre **DirectRouter** en la pestaña **Router**.
2. Toca **Encender Router** (concede los permisos de ubicación / dispositivos Wi-Fi cercanos y notificaciones si se solicitan).
3. Una vez activo, verás el **código QR de Wi-Fi**, el nombre de red (**SSID**), la **contraseña** y los datos del proxy (`192.168.49.1`, puerto `8080`).
4. En el dispositivo cliente, **escanea el código QR** o conéctate manualmente ingresando el **SSID** y la **contraseña** mostrados.
5. En los ajustes de esa red Wi-Fi en el dispositivo cliente, configura el **Proxy** en modo **Manual**:
   - **Host / Servidor proxy:** `192.168.49.1`
   - **Puerto:** `8080`
6. Guarda los cambios. El dispositivo ya navega a través de tu teléfono por HTTP/HTTPS (o SOCKS5 si la app cliente lo soporta).

### B) Uso con 2.º teléfono Android: Túnel VPN completo (TCP + UDP + DNS)

Ideal cuando quieres que **todas las aplicaciones** del segundo Android (incluyendo tráfico UDP, juegos, llamadas y resolución DNS) pasen por el router sin depender del proxy manual de Android.

1. Instala **DirectRouter** también en el segundo teléfono Android.
2. Conecta el segundo teléfono a la red **Wi-Fi Direct** del teléfono Router **sin configurar ningún proxy manual** en los ajustes Wi-Fi.
3. Abre **DirectRouter** en el segundo teléfono y ve a la pestaña **Cliente VPN**.
4. Verifica que el servidor apunte a `192.168.49.1` y puerto `8080` (valores por defecto) y toca **Probar conexión** para confirmar el enlace SOCKS5 con el router.
5. Toca **Conectar** y acepta el permiso de conexión VPN de Android. Todo el tráfico TCP, UDP y DNS quedará tunelizado mediante `hev-socks5-tunnel`.

---

## Características Principales

- **Sin root:** funciona con las APIs oficiales de Wi-Fi Direct (`WifiP2pManager`) y `VpnService` de Android.
- **Proxy híbrido en un solo puerto (`8080`):** detecta automáticamente conexiones **HTTP / HTTPS (`CONNECT`)** y **SOCKS5 (TCP + UDP Associate)** en el mismo socket.
- **Código QR de conexión rápida:** genera un QR estándar de Wi-Fi para conectar clientes al instante.
- **Modo Cliente VPN integrado:** usa `hev-socks5-tunnel` para encapsular todo el tráfico del dispositivo cliente (TCP + UDP + DNS) sobre el proxy SOCKS5 del router.
- **SSID, contraseña y banda configurables:** en Android 10+ permite definir credenciales persistentes y elegir banda **2.4 GHz** o **5 GHz**.
- **Servicio en primer plano con métricas en tiempo real:** muestra estado del grupo Wi-Fi Direct, clientes conectados y actividad del proxy/túnel.

---

## Requisitos

- **Android 8.0 o superior (API 26+)** para el funcionamiento general (Router Wi-Fi Direct, proxy híbrido y Cliente VPN).
- **Android 10 o superior (API 29+)** para configurar **SSID y contraseña fijos** y seleccionar la **banda 2.4 GHz / 5 GHz** (`WifiP2pConfig.Builder`). En Android 8–9 el sistema genera automáticamente el SSID y la contraseña del grupo Wi-Fi Direct.
- Permisos en tiempo de ejecución: Dispositivos Wi-Fi cercanos (`NEARBY_WIFI_DEVICES` en Android 13+) o Ubicación (Android 8–12) para gestionar grupos Wi-Fi Direct, y Notificaciones para el servicio en primer plano.

---

## Compilación Local

### Requisitos de compilación
- **JDK 17**
- **Android SDK** (compileSdk 34 / API 34) con `ANDROID_HOME` configurado o `local.properties` apuntando a tu SDK.

### Comandos

```bash
git clone https://github.com/soyaelithia-ui/DirectRouter.git
cd DirectRouter

# Ejecutar pruebas unitarias y compilar el APK debug
./gradlew testDebugUnitTest assembleDebug
```

El APK generado quedará en:

```text
app/build/outputs/apk/debug/app-debug.apk
```
