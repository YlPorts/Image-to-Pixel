# Image-to-Pixel para Android

Aplicación Kotlin que empaqueta el editor original **3.0.1** completo. Nombre visible:
**Image-to-Pixel**. Android 10 o posterior; compatible con ARM64, incluido el Samsung
Galaxy A15 SM-A155M. No contiene bibliotecas nativas específicas de una arquitectura,
por lo que la misma APK también funciona en el emulador x86_64.

## Obtener la APK

En GitHub Actions, abre **Android APK**, selecciona una ejecución correcta y descarga
el artifact **Image-to-Pixel-APK**. Descomprime e instala
`Image-to-Pixel-1.0.0-debug.apk`. `Image-to-Pixel-tests.apk` es exclusivamente la suite
de instrumentación; no es necesario instalarla para usar el editor.

El workflow se ejecuta al actualizar `android/**`, en Pull Requests y mediante
`workflow_dispatch`. Una vez incorporado a la rama principal, **Run workflow** permite
compilar manualmente desde Actions. Los workflows anteriores se conservan.

## Compilación local

Requisitos: Node 22, JDK 17 y Android SDK con `platforms;android-35` y
`build-tools;35.0.0`.

```sh
npm ci
npm test
npm run build:android -- --no-build
npm run check:android
cd android
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest
```

Salida: `android/app/build/outputs/apk/debug/app-debug.apk`.
`preBuild` prepara los assets a partir de `_site`; si falta la web, falla con una
instrucción explícita. `npm run build:android` también compila la web completa.
Gradle, AGP, Kotlin, dependencias Android y los recursos clásicos están fijados a
versiones concretas; `npm ci` usa el lockfile y el wrapper verifica el SHA-256.

## Conservación del editor

- `lib/core/cpu`, shaders WebGL, dithering, paletas, PNG, GIF, ZIP y sprite sheets
  conservan sus algoritmos originales.
- Se reutilizan `mountEditor`, HTML, CSS, Geist, JetBrains Mono, SVG, paneles,
  controles, animaciones y muestras originales.
- WebView carga `https://appassets.androidplatform.net/assets/site/editor/index.html`
  mediante `WebViewAssetLoader`. Ese dominio es un origen local interceptado; no se
  contacta un servidor ni se abre Chrome para editar.
- El paquete incluye también documentación, ejemplos y editor clásico. Font Awesome,
  Pickr, Inter y licencias se copian de paquetes npm fijados; no quedan CDN obligatorios.
- `android-asset-manifest.json` registra tamaño y SHA-256 de cada recurso.
  `check:android` verifica sus bytes y la identidad del JS/CSS/fuentes con `_site`.
- Si el contexto WebGL falla en modo automático, se utiliza la misma implementación
  CPU. El modo WebGL explícito sigue notificando el error para permitir pruebas reales.

## Integraciones

| Función original | Integración Android |
| --- | --- |
| Abrir imágenes, vídeos, paletas, JSON | `ACTION_OPEN_DOCUMENT`, selección múltiple y URIs concedidas por el sistema |
| Descargar PNG/GIF | MediaStore, `Pictures/Image-to-Pixel` |
| Descargar MP4/WebM | MediaStore, `Movies/Image-to-Pixel` |
| ZIP, JSON y archivos de paleta | `ACTION_CREATE_DOCUMENT`, ubicación elegida por el usuario |
| Compartir | Hoja del sistema, FileProvider/URIs MediaStore con permiso temporal de lectura |
| Portapapeles | API Android de texto e imágenes; sin permisos de almacenamiento amplios |
| Codificación de vídeo | WebCodecs original cuando está disponible; MediaCodec/MediaMuxer como alternativa |
| Rotar o pausar | WebView se mantiene durante rotación; se pausa vídeo y vista previa en segundo plano |
| Recuperar sesión | IndexedDB: fuentes originales, archivos individuales, selección, ajustes, historial y vista |

Los proyectos de trabajo se recuperan automáticamente al volver a abrir el editor.
La configuración se puede guardar/cargar mediante el JSON original; los lotes ZIP
conservan sus ajustes por archivo. No se inventa un formato de proyecto ajeno a la web.
La sesión se escribe después de cambios y al pausar. Un cierre forzado exactamente
durante una importación aún puede perder el último cambio no confirmado.

El puente acepta mensajes únicamente del marco principal del origen empaquetado.
Los archivos grandes se transfieren en bloques de 192 KiB a temporales, sin construir
una cadena base64 del archivo completo. No acepta rutas de archivos arbitrarias.
WebView tiene acceso `file://` desactivado, no carga recursos remotos y solo permite
enlaces externos tras una pulsación explícita. Las imágenes se procesan localmente.

## Offline y Lospec

El editor, sus muestras, las 24 paletas incorporadas, filtros y exportadores funcionan
desde la primera apertura sin red. Las búsquedas por slug de las paletas incorporadas
también se resuelven localmente. Las paletas obtenidas anteriormente se guardan.

**Buscar una paleta nueva que solo existe en Lospec necesita internet**, como en la
web original. Es una función opcional iniciada por su botón Fetch. Ese es el motivo
del único permiso `INTERNET`; no hay analítica, publicidad ni servicios obligatorios.
Se puede importar cualquier paleta local sin conexión. No es posible incluir de
antemano todas las futuras paletas del catálogo externo.

## Adaptaciones visuales mínimas

En pantallas de hasta 680 px se mantienen los botones originales y se distribuyen en
dos filas de 30 px, dentro de una barra de 78 px. Esto permite acceder a Deshacer,
Rehacer, tema y ayuda, que la web oculta en teléfonos, y evita cortar Exportar.
No se cambian tokens, tipografías, tamaño de iconos ni estilo de los controles.
En escritorio y horizontal se conserva el orden original. Los menús y diálogos
desplazan su contenido cuando la altura disponible es pequeña.

La vista usa los gestos originales y corrige el desplazamiento del centro al pellizcar
y la continuación del gesto cuando se levanta uno de los dedos. Android aplica las
áreas seguras y permite mostrar temporalmente las barras mediante un deslizamiento.

## Comprobaciones y límites

```sh
npx playwright install chromium
npm run test:android-web
```

La prueba compara temas claro/oscuro a 360×800, 800×360 y 1440×900; verifica fuentes,
colores, paneles, botones accesibles, los 17 dithers con igualdad exacta de RGBA,
PNG/ZIP/GIF/sprite sheet/tileset/JSON, importación de paleta y recuperación de sesión.
El puente de almacenamiento se simula en esta prueba de navegador; **no sustituye
las pruebas Android**. Las capturas y `comparison.json` se publican como artifact.

La suite Android instalada prueba WebView offline, importación, PNG válido en
MediaStore, rotaciones, restauración de imágenes y deshacer, selector de archivos,
botón Atrás y codificador nativo. El workflow desactiva Wi-Fi/datos y activa modo avión.
Las capturas reales y logs están en **Android-device-tests**.

La reproducción de cada códec depende de Android System WebView y del hardware.
Se mantienen los errores y alternativas originales. Como en la web, importar GIF
carga una imagen; la exportación GIF animada parte de imágenes o vídeo. Los vídeos
no incorporan audio, igual que el codificador original. La alternativa nativa puede
producir un bitstream diferente, aunque recibe los mismos píxeles procesados.

No se afirma rendimiento, consumo térmico ni validación física en un Galaxy A15
sin medirlo en ese teléfono. Los archivos grandes, lotes extensos y GIF/sheets con
muchos fotogramas siguen sujetos a los límites de memoria del motor original.
No se baja la resolución ni la calidad silenciosamente para evitar esos límites.
