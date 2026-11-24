# Política de Seguridad / Security Policy

## Versiones soportadas / Supported Versions

| Versión | Estado |
| ------- | ------ |
| `1.2.x` | :white_check_mark: Soportada / Supported |
| `< 1.2.0` | :x: Sin soporte (actualizar a `v1.2.0+`) / Unsupported |

---

## Modelo de seguridad de DirectRouter (`v1.2.0`)

DirectRouter está diseñado para compartir conexión localmente sin requerir root, minimizando la superficie de exposición del dispositivo Host:

1. **Enlace local seguro por defecto (*Local-only bind*):**
   - En modo **Wi-Fi Direct**, el proxy escucha únicamente en la IP del grupo (`192.168.49.1`).
   - En modo **Tethering Nativo**, el proxy enlaza por defecto únicamente a la IP de la interfaz de compartición detectada (`ap0`, `swlan0`, `rndis0`, etc.) o a una IP local específica validada, evitando exponer el proxy a redes Wi-Fi públicas.
   - El enlace a todas las interfaces (`0.0.0.0`) solo está disponible mediante activación explícita en Modo Experto con indicador de advertencia visible.
2. **Protección Anti-SSRF y anti-bucle (`ProxySecurityPolicy`):**
   - Activa por defecto en conexiones TCP (`OutboundConnector`) y datagramas UDP (`Socks5Handler` `UDP ASSOCIATE`).
   - Bloquea conexiones dirigidas a loopback (`127.0.0.0/8`, `::1`), direcciones comodín (`0.0.0.0`, `::`), link-local (`169.254.0.0/16`, `fe80::/10`) y al propio puerto del proxy sobre IPs locales del Host (respondiendo HTTP `403 Forbidden` o SOCKS5 `0x02`).
3. **Límite de conexiones concurrentes:**
   - Aplica un tope seguro configurable (por defecto `256`, rango `16–512`) para evitar agotamiento de hilos o memoria ante ráfagas o abuso local.
4. **Autenticación opcional del proxy:**
   - Soporta autenticación usuario/contraseña para **HTTP Basic** (`Proxy-Authorization` / `407 Proxy Authentication Required`) y **SOCKS5 RFC 1929** (`0x02`), utilizando comparación de credenciales en tiempo constante (`MessageDigest.isEqual`).
   - Cuando la autenticación está habilitada, las conexiones **SOCKS4/4a** se rechazan automáticamente al no soportar contraseña.
   - Compatible con el **Cliente VPN** integrado (`hev_tunnel.yml`).
5. **Sanitización estricta de `/proxy.pac`:**
   - Valida estrictamente direcciones IPv4/IPv6 y puertos numéricos al responder `/proxy.pac`, rechazando encabezados `Host:` maliciosos para prevenir inyección de código JavaScript.
6. **Endurecimiento de la aplicación Android:**
   - `android:allowBackup="false"` para impedir la extracción de preferencias o credenciales mediante copias de seguridad ADB/sistema.
   - `android:usesCleartextTraffic="false"` a nivel de plataforma Android.
   - Permisos mínimos acotados por nivel de API (`NEARBY_WIFI_DEVICES` con `neverForLocation` en Android 13+; `ACCESS_FINE_LOCATION` limitado a `maxSdkVersion="32"`).
7. **Privacidad en notificaciones:**
   - Modo privacidad opcional (`VISIBILITY_SECRET`) que oculta SSID e IP en la barra de estado y en la pantalla bloqueada.

---

## Reportar una vulnerabilidad / Reporting a Vulnerability

Si descubres un fallo de seguridad en DirectRouter, **por favor no abras un issue público**.

Repórtalo de forma privada y responsable mediante **GitHub Security Advisories**:

👉 **[Reportar vulnerabilidad en privado / Report a vulnerability privately](https://github.com/soyaelithia-ui/DirectRouter/security/advisories/new)**

### Qué incluir en tu reporte / What to include
- Versión de DirectRouter y versión de Android / dispositivo afectado.
- Descripción técnica del fallo y su impacto potencial (por ejemplo, omisión anti-SSRF, fuga de credenciales, exposición de bind o denegación de servicio).
- Pasos claros de reproducción o prueba de concepto (PoC).
- Cualquier sugerencia de mitigación si la tienes.

### Tiempos de respuesta / Response Timeline
- **Acuse de recibo:** dentro de 3 a 5 días hábiles.
- **Evaluación y corrección:** trabajaremos contigo para validar el problema, publicar un parche en una nueva versión y coordinar la divulgación responsable una vez disponible la corrección.
