# Contribuir a DirectRouter / Contributing to DirectRouter

¡Gracias por tu interés en contribuir a **DirectRouter**! Este es un proyecto de código abierto y libre bajo la [Licencia MIT](LICENSE). Aceptamos contribuciones, reportes de errores, mejoras de documentación y traducciones tanto en **español** como en **inglés**.

Thank you for your interest in contributing to **DirectRouter**! This is an open-source project under the [MIT License](LICENSE). Contributions, bug reports, documentation improvements, and fixes are welcome in both **Spanish** and **English**.

---

## Requisitos de desarrollo / Development Requirements

- **JDK 17**
- **Android SDK** (`compileSdk 34` / API 34, `minSdk 26` / Android 8.0+)
- `ANDROID_HOME` configurado en tu entorno (o archivo `local.properties` con `sdk.dir=/ruta/a/tu/android-sdk`)
- **Android Studio** (recomendado) o línea de comandos con **Gradle Wrapper** (`./gradlew`)

---

## Compilación y pruebas / Building & Testing

```bash
# Clonar tu fork del repositorio / Clone your fork
git clone https://github.com/<tu-usuario>/DirectRouter.git
cd DirectRouter

# Ejecutar todas las pruebas unitarias / Run all unit tests
./gradlew testDebugUnitTest

# Compilar el APK debug / Build the debug APK
./gradlew assembleDebug
```

El APK generado se ubica en `app/build/outputs/apk/debug/app-debug.apk`.

---

## Estructura del proyecto / Project Structure

```text
app/src/main/java/com/directrouter/app/
├── proxy/   # Motor proxy multiprotocolo (HTTP/HTTPS, PAC, SOCKS4/4a, SOCKS5 TCP/UDP, ProxySecurityPolicy)
├── host/    # Servicios HostRouterService, Wi-Fi Direct, detección de interfaces y notificaciones
├── client/  # Cliente VPN (DirectVpnService y puente JNI HevTunnelBridge sobre hev-socks5-tunnel)
├── data/    # Preferencias DataStore (RouterSettings, SettingsRepository) y validadores (ValidationUtils)
└── ui/      # Pantallas y componentes Jetpack Compose + Material 3 (Claro/Oscuro/AMOLED/Material You)
```

---

## Convenciones de código / Coding Conventions

- **Sin root / No root:** toda funcionalidad debe depender exclusivamente de APIs oficiales de Android (`WifiP2pManager`, `VpnService`, sockets estándar).
- **Seguridad por defecto / Secure by default:** mantén el enlace local por defecto, la protección anti-SSRF, la validación estricta de entradas y evita exponer credenciales en logs o notificaciones con modo privacidad activo.
- **Kotlin y Jetpack Compose:** sigue el estilo idiomático de Kotlin, usa `StateFlow` / `DataStore` para estado persistente y reutiliza los componentes y colores del tema Material 3.
- **Interfaz bilingüe:** conserva las cadenas de texto coherentes en español e inglés donde corresponda (`tr(es, en)`).
- **Pruebas:** añade o actualiza pruebas unitarias en `app/src/test/` cuando modifiques lógica de proxy, validación, seguridad o configuración.

---

## Flujo de Pull Request / Pull Request Workflow

1. Haz **fork** del repositorio y crea una rama descriptiva desde `main`:
   ```bash
   git checkout -b feat/mi-mejora
   # o / or
   git checkout -b fix/correccion-proxy
   ```
2. Realiza cambios enfocados y commits claros (por ejemplo, estilo Conventional Commits: `feat:`, `fix:`, `docs:`, `test:`).
3. Verifica localmente que las pruebas y la compilación pasan sin errores:
   ```bash
   ./gradlew testDebugUnitTest assembleDebug
   ```
4. Abre un **Pull Request** hacia `main` explicando:
   - Qué problema resuelve o qué mejora aporta.
   - Cómo se probó (pruebas unitarias y/o dispositivos Android reales).
   - Capturas de pantalla si hay cambios visuales en la interfaz.

---

## Reportar errores o proponer mejoras / Reporting Bugs & Feature Requests

- **Errores / Bugs:** abre un issue usando la [plantilla de reporte de error](.github/ISSUE_TEMPLATE/bug_report.md) indicando versión de Android, fabricante/OEM y modo usado (`Wi-Fi Direct`, `Tethering Nativo` o `Cliente VPN`).
- **Propuestas / Features:** usa la [plantilla de solicitud de función](.github/ISSUE_TEMPLATE/feature_request.md).
- **Vulnerabilidades de seguridad:** **no** abras un issue público; sigue las instrucciones de [SECURITY.md](SECURITY.md).
