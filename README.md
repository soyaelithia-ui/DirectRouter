# DirectRouter

**DirectRouter** es una aplicación Android **sin necesidad de root** que convierte tu teléfono en un **Router Wi-Fi Direct** o en un **servidor proxy sobre Tethering Nativo** con un **proxy local multiprotocolo** escuchando en un solo puerto (`8080` por defecto), además de incluir un **modo Cliente VPN** basado en `hev-socks5-tunnel` para tunelizar todo el tráfico (TCP, UDP y DNS) desde un segundo dispositivo Android.

---

## Descarga e Instalación

Descarga el APK listo para instalar desde la última versión publicada en GitHub Releases:

👉 **[Descargar última versión (APK) — GitHub Releases](https://github.com/soyaelithia-ui/DirectRouter/releases/latest)**

### Pasos para instalar el APK en Android
1. Abre el enlace anterior desde tu teléfono Android y descarga el archivo `DirectRouter-v1.1.0-debug.apk` en la sección **Assets**.
2. Toca el archivo descargado desde tus notificaciones o desde el gestor de archivos (**Descargas**).
3. Si Android lo solicita, habilita **Instalar aplicaciones desconocidas** (*Ajustes → Seguridad / Aplicaciones → Instalar apps desconocidas* para tu navegador o gestor de archivos).
4. Confirma tocando **Instalar** y luego abre **DirectRouter**.

---

## Novedades en v1.1.0

- **Nuevo modo Tethering Nativo:** comparte internet a través de la **Zona Wi-Fi nativa**, **tethering USB** o **red local (LAN)** sin iniciar ni tocar Wi-Fi Direct y **sin necesidad de instalar ninguna app VPN en ningún teléfono**. Detecta automáticamente las IPs locales activas y ofrece configuración rápida por proxy manual o URL PAC.
- **Soporte ampliado de protocolos en un solo puerto:** ahora el proxy multiprotocolo atiende automáticamente **HTTP/HTTPS (`CONNECT`)**, **WebSocket (`ws://`/`wss://`)**, **Auto-Proxy PAC (`http://<ip>:<puerto>/proxy.pac`)**, **SOCKS4 / SOCKS4a** y **SOCKS5 (TCP + UDP Associate)** en el mismo puerto.
- **Correcciones de estabilidad y rendimiento del proxy:** pool de hilos no bloqueante escalado hasta **1024 hilos** para evitar cuellos de botella y timeouts bajo múltiples conexiones simultáneas, más conexión saliente multicandidato **IPv4/IPv6** con reintento automático entre direcciones resueltas.
- **Correcciones de estabilidad en Cliente VPN:** integración de **`mapdns`** para resolución DNS estable dentro del túnel, reserva explícita de red mediante **`requestNetwork`**, captura de tráfico **IPv6** y **MTU 1500** para evitar bloqueos y tiempos de espera agotados.

---

## Guías de Uso

### A) Modo Tethering Nativo (Zona Wi-Fi nativa / USB / LAN, sin Wi-Fi Direct y sin app VPN)

Ideal cuando quieres aprovechar el **Punto de acceso / Zona Wi-Fi nativa**, **tethering USB** o tu **LAN** para compartir internet a través del proxy de DirectRouter **sin tocar Wi-Fi Direct** y **sin instalar ninguna app VPN en el host ni en los clientes**.

1. En el teléfono con internet, activa tu **Zona Wi-Fi portátil**, **tethering USB** o conéctate a la **LAN** compartida.
2. Abre **DirectRouter** en la pestaña **Router**, selecciona el modo **Tethering Nativo** y toca **Encender Router**.
3. DirectRouter detectará tus interfaces activas y mostrará las direcciones del proxy (por ejemplo `http://<ip>:<puerto>`) y la URL de **Auto-Proxy PAC** (`http://<ip>:<puerto>/proxy.pac`).
4. En el dispositivo cliente (Android, iOS, Windows, macOS, Linux o consola), conecta a esa red y configura:
   - **Proxy automático (PAC):** pega `http://<ip>:<puerto>/proxy.pac` (recomendado), o
   - **Proxy manual:** ingresa la IP mostrada y el puerto `8080`.
5. Guarda los cambios. Todo el tráfico HTTP/HTTPS, WebSocket y aplicaciones compatibles con SOCKS4/4a o SOCKS5 saldrá por el teléfono sin instalar nada en el cliente.

### B) Modo Router Wi-Fi Direct (1 solo teléfono + clientes sin app)

Ideal para compartir internet mediante **Wi-Fi Direct** cuando no deseas usar o no tienes disponible la Zona Wi-Fi nativa.

1. En el teléfono que tiene internet (datos móviles o Wi-Fi), abre **DirectRouter** en la pestaña **Router** en modo **Wi-Fi Direct**.
2. Toca **Encender Router** (concede los permisos de ubicación / dispositivos Wi-Fi cercanos y notificaciones si se solicitan).
3. Una vez activo, verás el **código QR de Wi-Fi**, el nombre de red (**SSID**), la **contraseña**, la dirección del proxy (`192.168.49.1:8080`) y la URL **PAC** (`http://192.168.49.1:8080/proxy.pac`).
4. En el dispositivo cliente, **escanea el código QR** o conéctate manualmente ingresando el **SSID** y la **contraseña** mostrados.
5. En los ajustes de esa red Wi-Fi en el dispositivo cliente, configura:
   - **Auto-Proxy (PAC):** `http://192.168.49.1:8080/proxy.pac`, o
   - **Proxy Manual:** Host `192.168.49.1`, Puerto `8080`.
6. Guarda los cambios. El dispositivo ya navega a través de tu teléfono por HTTP/HTTPS, WebSocket, SOCKS4/4a o SOCKS5.

### C) Uso con 2.º teléfono Android: Túnel VPN completo (TCP + UDP + DNS + IPv6)

Ideal cuando quieres que **todas las aplicaciones** del segundo Android (incluyendo tráfico UDP, juegos, llamadas, IPv6 y resolución DNS) pasen por el router sin depender del proxy manual de Android.

1. Instala **DirectRouter** también en el segundo teléfono Android.
2. Conecta el segundo teléfono a la red **Wi-Fi Direct** (o red del **Tethering Nativo**) del teléfono Router **sin configurar ningún proxy manual** en los ajustes Wi-Fi.
3. Abre **DirectRouter** en el segundo teléfono y ve a la pestaña **Cliente VPN**.
4. Verifica que el servidor apunte a la IP del router (`192.168.49.1` en Wi-Fi Direct o la IP local en Tethering Nativo) y puerto `8080`, y toca **Probar conexión** para confirmar el enlace SOCKS5.
5. Toca **Conectar** y acepta el permiso de conexión VPN de Android. Todo el tráfico TCP, UDP, DNS (`mapdns`) e IPv6 quedará tunelizado mediante `hev-socks5-tunnel` con MTU 1500.

---

## Características Principales

- **Sin root:** funciona con las APIs oficiales de Android (`WifiP2pManager`, `VpnService` y sockets locales estándar).
- **Dos modos de compartición:** **Wi-Fi Direct** y **Tethering Nativo** (Zona Wi-Fi nativa, USB o LAN sin tocar Wi-Fi Direct ni requerir app VPN).
- **Proxy multiprotocolo en un solo puerto (`8080`):** detecta y enruta automáticamente en el mismo socket:
  - **HTTP / HTTPS (`CONNECT`)**
  - **WebSocket (`ws://` / `wss://`)**
  - **Auto-Proxy PAC (`http://<ip>:<puerto>/proxy.pac`)**
  - **SOCKS4 / SOCKS4a**
  - **SOCKS5 (TCP + UDP Associate)**
- **Alta concurrencia y tolerancia a redes dual-stack:** pool no bloqueante de hasta **1024 hilos** y conexión saliente multicandidato **IPv4/IPv6**.
- **Código QR de conexión rápida:** genera un QR estándar de Wi-Fi para conectar clientes al instante en modo Wi-Fi Direct.
- **Modo Cliente VPN integrado:** usa `hev-socks5-tunnel` con **`mapdns`**, **`requestNetwork`**, soporte **IPv6** y **MTU 1500** para tunelizar todo el tráfico del dispositivo cliente sobre SOCKS5.
- **SSID, contraseña y banda configurables:** en Android 10+ permite definir credenciales persistentes y elegir banda **2.4 GHz** o **5 GHz** en modo Wi-Fi Direct.
- **Servicio en primer plano con métricas en tiempo real:** muestra estado del router, interfaces activas, clientes conectados y actividad del proxy/túnel.

---

## Requisitos

- **Android 8.0 o superior (API 26+)** para el funcionamiento general (Router Wi-Fi Direct, Tethering Nativo, proxy multiprotocolo y Cliente VPN).
- **Android 10 o superior (API 29+)** para configurar **SSID y contraseña fijos** y seleccionar la **banda 2.4 GHz / 5 GHz** (`WifiP2pConfig.Builder`) en modo Wi-Fi Direct. En Android 8–9 el sistema genera automáticamente el SSID y la contraseña del grupo Wi-Fi Direct.
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
