# DirectRouter

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Open Source](https://img.shields.io/badge/Open%20Source-Public%20Repo-10B981.svg)](https://github.com/soyaelithia-ui/DirectRouter)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84.svg)](#requisitos)
[![No Root](https://img.shields.io/badge/Root-No%20Root%20Required-38BDF8.svg)](#caracter%C3%ADsticas-principales)

**DirectRouter** es una aplicación Android **de código abierto y sin necesidad de root** que convierte tu teléfono en un **Router Wi-Fi Direct** o en un **servidor proxy sobre Tethering Nativo** con un **proxy local multiprotocolo seguro** escuchando en un solo puerto (`8080` por defecto), además de incluir un **modo Cliente VPN** basado en `hev-socks5-tunnel` para tunelizar todo el tráfico (TCP, UDP, DNS e IPv6) desde un segundo dispositivo Android.

---

## Descarga e Instalación

Descarga el APK listo para instalar desde la última versión publicada en GitHub Releases:

👉 **[Descargar última versión (APK) — GitHub Releases](https://github.com/soyaelithia-ui/DirectRouter/releases/latest)**

### Pasos para instalar el APK en Android
1. Abre el enlace anterior desde tu teléfono Android y descarga el archivo `DirectRouter-v1.2.0-debug.apk` en la sección **Assets**.
2. Toca el archivo descargado desde tus notificaciones o desde el gestor de archivos (**Descargas**).
3. Si Android lo solicita, habilita **Instalar aplicaciones desconocidas** (*Ajustes → Seguridad / Aplicaciones → Instalar apps desconocidas* para tu navegador o gestor de archivos).
4. Confirma tocando **Instalar** y luego abre **DirectRouter**.

---

## Novedades en v1.2.0

- **Endurecimiento de seguridad integral (`ProxySecurityPolicy`):**
  - **Enlace local seguro por defecto:** el proxy enlaza únicamente a la interfaz local de **Wi-Fi Direct** (`192.168.49.1`) o a la interfaz activa de **Tethering Nativo** (`ap0`, `swlan0`, `rndis0`, etc.) para no exponer el proxy en redes Wi-Fi públicas.
  - **Protección Anti-SSRF (TCP y UDP):** bloquea por defecto el acceso a `127.0.0.0/8`, `::1`, `0.0.0.0`, `::`, `169.254.0.0/16`, `fe80::/10` y al propio puerto del proxy en el Host.
  - **Autenticación opcional en el proxy:** soporte para **HTTP Basic (`Proxy-Authorization` / `407`)** y **SOCKS5 RFC 1929 (`0x02`)** con comparación en tiempo constante (rechazando SOCKS4 automáticamente si está activa) y soporte de credenciales en el **Cliente VPN**.
  - **Límite de conexiones concurrentes y `/proxy.pac` sanitizado:** tope configurable (por defecto `256`, rango `16–512`) y validación estricta del encabezado `Host:` en PAC para evitar inyección JavaScript.
  - **Manifiesto endurecido:** `android:allowBackup="false"` y `android:usesCleartextTraffic="false"`.
- **Control seguro de IP y puerto:** modos *Automático seguro*, *IP específica* (con selector de interfaces activas y validación IPv4 estricta) y *Todas las interfaces (`0.0.0.0`)* con advertencia visible, más generador de **puerto aleatorio (`10000–60000`)** y **reinicio seguro en caliente**.
- **Temas Claro, Oscuro, Negro AMOLED y Material You:** selector de tema (`Sistema / Claro / Oscuro / AMOLED`), acceso rápido en la barra superior y soporte de colores dinámicos en Android 12+.
- **Notificaciones granulares y privacidad:** control independiente de **velocidad en vivo**, **Modo privacidad** (oculta SSID e IP en la notificación y pantalla bloqueada), botón rápido **Apagar**, **alertas de clientes/errores** con sonido y vibración configurables, y accesos directos a los canales del sistema.
- **Nueva identidad visual Mesh-Node:** icono adaptativo y temático (Android 13+ `<monochrome>`), iconos vectoriales propios en la barra de estado y emblema con indicador de estado en vivo en la barra superior.
- **Diagnóstico de compatibilidad OEM:** tarjeta integrada con fabricante/modelo, API, permisos, optimización de batería, interfaces activas y recomendaciones específicas para **Xiaomi/HyperOS**, **Samsung OneUI**, **Huawei/Honor** y **Oppo/Vivo**.
- **Proyecto público y de código libre (MIT):** licencia formal [MIT](LICENSE), guía de contribución ([CONTRIBUTING.md](CONTRIBUTING.md)), política de seguridad ([SECURITY.md](SECURITY.md)) y plantillas de issues.

---

## Guías de Uso

### A) Modo Tethering Nativo (Zona Wi-Fi nativa / USB / LAN, sin Wi-Fi Direct y sin app VPN)

Ideal cuando quieres aprovechar el **Punto de acceso / Zona Wi-Fi nativa**, **tethering USB** o tu **LAN** para compartir internet a través del proxy de DirectRouter **sin tocar Wi-Fi Direct** y **sin instalar ninguna app VPN en el host ni en los clientes**.

1. En el teléfono con internet, activa tu **Zona Wi-Fi portátil**, **tethering USB** o conéctate a la **LAN** compartida.
2. Abre **DirectRouter** en la pestaña **Router**, selecciona el modo **Tethering Nativo** y toca **Encender Router**.
3. DirectRouter enlazará de forma segura a tu interfaz local activa (o a la IP elegida en Ajustes) y mostrará la dirección del proxy (`http://<ip>:<puerto>`) y la URL de **Auto-Proxy PAC** (`http://<ip>:<puerto>/proxy.pac`).
4. En el dispositivo cliente (Android, iOS, Windows, macOS, Linux o consola), conecta a esa red y configura:
   - **Proxy automático (PAC):** pega `http://<ip>:<puerto>/proxy.pac` (recomendado), o
   - **Proxy manual:** ingresa la IP mostrada y el puerto configurado (`8080` por defecto).
5. Guarda los cambios. Todo el tráfico HTTP/HTTPS, WebSocket y aplicaciones compatibles con SOCKS4/4a o SOCKS5 saldrá por el teléfono sin instalar nada en el cliente.

### B) Modo Router Wi-Fi Direct (1 solo teléfono + clientes sin app)

Ideal para compartir internet mediante **Wi-Fi Direct** cuando no deseas usar o no tienes disponible la Zona Wi-Fi nativa.

1. En el teléfono que tiene internet (datos móviles o Wi-Fi), abre **DirectRouter** en la pestaña **Router** en modo **Wi-Fi Direct**.
2. Toca **Encender Router** (concede los permisos de ubicación / dispositivos Wi-Fi cercanos y notificaciones si se solicitan).
3. Una vez activo, verás el **código QR de Wi-Fi**, el nombre de red (**SSID**), la **contraseña**, la dirección del proxy (`192.168.49.1:8080`), el badge de seguridad activo y la URL **PAC** (`http://192.168.49.1:8080/proxy.pac`).
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
4. Verifica que el servidor apunte a la IP del router (`192.168.49.1` en Wi-Fi Direct o la IP local en Tethering Nativo) y el puerto configurado. Si activaste la autenticación en el Host, despliega **Autenticación del Proxy** e introduce el usuario y la contraseña.
5. Toca **Probar conexión** para confirmar el enlace SOCKS5 y luego **Conectar** aceptando el permiso de conexión VPN de Android. Todo el tráfico TCP, UDP, DNS (`mapdns`) e IPv6 quedará tunelizado mediante `hev-socks5-tunnel` con MTU 1500.

---

## Características Principales

- **Sin root:** funciona con las APIs oficiales de Android (`WifiP2pManager`, `VpnService` y sockets locales estándar).
- **Dos modos de compartición:** **Wi-Fi Direct** y **Tethering Nativo** (Zona Wi-Fi nativa, USB o LAN sin tocar Wi-Fi Direct ni requerir app VPN).
- **Proxy multiprotocolo en un solo puerto (`8080` por defecto, configurable `1024–65535`):** detecta y enruta automáticamente en el mismo socket:
  - **HTTP / HTTPS (`CONNECT`)**
  - **WebSocket (`ws://` / `wss://`)**
  - **Auto-Proxy PAC (`http://<ip>:<puerto>/proxy.pac`)**
  - **SOCKS4 / SOCKS4a**
  - **SOCKS5 (TCP + UDP Associate)**
- **Seguridad reforzada:** enlace local seguro por interfaz, bloqueo Anti-SSRF en TCP/UDP, límite de conexiones concurrentes, autenticación opcional HTTP Basic / SOCKS5 RFC 1929 y sanitización de PAC.
- **Alta concurrencia y tolerancia a redes dual-stack:** pool no bloqueante y conexión saliente multicandidato **IPv4/IPv6** con reintento automático entre direcciones resueltas.
- **Código QR de conexión rápida:** genera un QR estándar de Wi-Fi para conectar clientes al instante en modo Wi-Fi Direct.
- **Modo Cliente VPN integrado:** usa `hev-socks5-tunnel` con **`mapdns`**, **`requestNetwork`**, soporte **IPv6**, credenciales opcionales y **MTU 1500** para tunelizar todo el tráfico del dispositivo cliente sobre SOCKS5.
- **Personalización, notificaciones y diagnóstico OEM:** temas Claro / Oscuro / Negro AMOLED / Material You, notificaciones configurables con modo privacidad y alertas, SSID/contraseña/banda (2.4 o 5 GHz en Android 10+) y diagnóstico de compatibilidad integrado.

---

## Requisitos

- **Android 8.0 o superior (API 26+)** para el funcionamiento general (Router Wi-Fi Direct, Tethering Nativo, proxy multiprotocolo y Cliente VPN).
- **Android 10 o superior (API 29+)** para configurar **SSID y contraseña fijos** y seleccionar la **banda 2.4 GHz / 5 GHz** (`WifiP2pConfig.Builder`) en modo Wi-Fi Direct. En Android 8–9 el sistema genera automáticamente el SSID y la contraseña del grupo Wi-Fi Direct.
- **Android 12+ (API 31+)** para colores dinámicos (*Material You*) y **Android 13+ (API 33+)** para iconos temáticos monocromáticos.
- Permisos en tiempo de ejecución: Dispositivos Wi-Fi cercanos (`NEARBY_WIFI_DEVICES` en Android 13+) o Ubicación (Android 8–12) para gestionar grupos Wi-Fi Direct, y Notificaciones para el servicio en primer plano.

---

## Compilación Local

### Requisitos de compilación
- **JDK 17**
- **Android SDK** (`compileSdk 34` / API 34) con `ANDROID_HOME` configurado o `local.properties` apuntando a tu SDK.

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

---

## Licencia y Código Abierto (Open Source)

**DirectRouter** es software libre y de código abierto distribuido bajo la **[Licencia MIT](LICENSE)**. Puedes usar, estudiar, modificar y redistribuir el código libremente respetando los avisos de copyright y licencia.

- 📄 **Licencia:** [LICENSE](LICENSE) (MIT License)
- 🤝 **Cómo contribuir:** [CONTRIBUTING.md](CONTRIBUTING.md)
- 🔐 **Política de seguridad:** [SECURITY.md](SECURITY.md)
- 🐛 **Reportar errores o proponer mejoras:** [GitHub Issues](https://github.com/soyaelithia-ui/DirectRouter/issues)

### Atribuciones de software de terceros
DirectRouter utiliza y agradece a los siguientes proyectos de código abierto:
- **[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)** y sus bindings JNI Android **[wgtunnel/core (`com.wgtunnel:hevtunnel`)](https://github.com/wgtunnel/core)** — Licencia MIT
- **[ZXing (`com.google.zxing:core`)](https://github.com/zxing/zxing)** — Licencia Apache 2.0
- **[AndroidX / Jetpack Compose](https://developer.android.com/jetpack/compose)** y **[Kotlin / Coroutines](https://kotlinlang.org/)** — Licencia Apache 2.0
