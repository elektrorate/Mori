# Google Drive: guardar solo en la nube

La integracion esta implementada para Windows y Android. Esta desactivada por
defecto. Los archivos se descargan a almacenamiento temporal privado, se suben
a la carpeta elegida y se eliminan localmente solo despues de verificar en Drive
el ID, el tamano, el MD5, la carpeta y que el archivo no este en la papelera.

Si una transferencia falla, el archivo temporal y el trabajo pendiente se
conservan. Los reintentos se realizan desde Ajustes. Una limpieza pendiente
vuelve a comprobar el contenido antes de borrar, sin crear otra copia en Drive.

## Configuracion

1. Habilitar Google Drive API y Google Picker API en el mismo proyecto de Google.
2. Configurar el consentimiento OAuth con el permiso `drive.file` y los usuarios
   de prueba necesarios.
3. Windows: crear un cliente OAuth de tipo Aplicacion de escritorio. Restablecer
   cualquier secreto expuesto y descargar las credenciales actualizadas fuera
   del repositorio. En Mori, Ajustes > Google Drive > Importar credenciales de
   Windows abre un selector nativo. Las credenciales se guardan en el gestor de
   credenciales de Windows, nunca en JavaScript ni localStorage.
4. Android: registrar un cliente OAuth para `com.mori.downloader` y la huella
   SHA-1 del certificado que firma el APK instalado. La huella se obtiene con
   `gradlew.bat signingReport` desde `android`. Android requiere Google Play
   services; no utiliza el secreto del cliente de escritorio.
5. Conectar la cuenta, elegir la carpeta con el selector de Google y activar
   Guardar solo en Drive. Conectar no activa automaticamente el destino.

La integracion incluye descargas individuales, lotes, PDFs y enlaces compartidos
en Android. El historial guarda enlaces para Abrir en Drive, no rutas locales
a archivos ya eliminados. Los recibos nativos permiten recuperar resultados
si la interfaz se cierra antes de guardar el historial.

## Verificacion Y Compilacion

- `npm test` ejecuta pruebas de JavaScript con puentes nativos simulados.
- Windows requiere Rust/Cargo y las herramientas de compilacion C++/WebView2 de
  Tauri. Ejecutar `cargo test` en `src-tauri`, actualizar Cargo.lock y despues
  `npm run tauri:build`.
- Android requiere JDK 21, Android SDK 36 y el NDK configurado en el proyecto.
  Ejecutar `gradlew.bat :app:testDebugUnitTest --no-daemon` en `android` y despues
  `npm run build:android`.
- Desde Windows usar `npm run build:android:windows`. Los binarios nativos
  incluidos permiten compilar esta copia sin descargar el NDK.
- Las pruebas simuladas no sustituyen la compilacion ni pruebas reales de OAuth,
  seleccion de carpeta, perdida de red, cuota, reinicio y limpieza local.

No hay garantia de continuar con la aplicacion cerrada: al volver a abrirla,
los trabajos pendientes se reintentan manualmente. Antes de usar con contenido
importante, probar un archivo pequeno y confirmar tanto la copia en Drive como
la eliminacion del temporal. Desconectar no elimina archivos pendientes ni
archivos en Drive. Desinstalar la aplicacion si borra sus temporales privados.

Detalles nativos: `src-tauri/DRIVE.md` y `android/MORI_DRIVE.md`.

## Prueba Real En Android

1. Instalar el APK de depuracion de `android/app/build/outputs/apk/debug/`.
   Si hay una instalacion del autor original con otra firma, Android no permite
   actualizarla con esta firma. Antes de desinstalar, respaldar historial y
   trabajos pendientes: desinstalar elimina los datos privados de la aplicacion.
2. Abrir Ajustes > Google Drive > Conectar / elegir carpeta.
3. Autorizar la cuenta registrada como usuario de prueba y seleccionar la carpeta.
4. Activar Guardar solo en Drive y pulsar Probar subida (.txt).
5. Confirmar el mensaje de subida verificada y temporal eliminado, y comprobar
   `Mori_drive_test_*.txt` en la carpeta de Drive. El archivo de prueba se puede
   eliminar desde Drive despues de comprobarlo.
6. Si aparece un error o limpieza pendiente, no considerar la prueba completa.
   Revisar el trabajo en Ajustes, reintentar y conservar el mensaje de error.

El certificado de depuracion local registrado para esta maquina tiene SHA-1:

`F3:D0:10:B9:BB:54:64:77:96:43:28:DB:58:FF:8B:54:97:0D:BB:A6`

No es una firma de produccion. No perder `~/.android/debug.keystore` si se quieren
instalar futuras actualizaciones compatibles con este APK.

## Estado De Este Equipo

- Rust/MSVC y el Windows SDK estan instalados. Smart App Control bloquea los
  ejecutables locales de compilacion de Cargo (error 4551, evento 3077). No se
  ha desactivado la proteccion; falta compilar Windows en un entorno autorizado.
- JDK 21 y Android SDK estan instalados. `JAVA_HOME` y `ANDROID_HOME` estan
  configurados para el usuario. Reabrir la terminal si estaba abierta al instalar.
- Las siete pruebas Java nativas y la compilacion Android se ejecutaron correctamente.
  El usuario confirmo en su telefono la autorizacion, la carpeta elegida, la
  subida real del archivo de prueba y la eliminacion del temporal. No se han
  probado todos los proveedores multimedia ni escenarios de fallos de red/cuota.

## Compilacion Windows En GitHub Actions

El workflow `.github/workflows/build-drive-windows.yml` ejecuta las pruebas
JavaScript, las pruebas Rust nativas y genera instaladores NSIS/MSI en un runner
Windows. Los archivos se publican como artefactos de la ejecucion, no como una
release. No necesita secretos de Google, claves de firma Android ni un login de
Google. Importar las credenciales OAuth actualizadas solo en la aplicacion local.

Los instaladores no tienen firma Authenticode de produccion. Compilar en GitHub
no garantiza que Smart App Control permita ejecutar un instalador descargado
sin firma. No desactivar automaticamente las protecciones del equipo.

### Resultado Verificado

La ejecucion [37227097787](https://github.com/elektrorate/Mori/actions/runs/37227097787)
compilo el commit `c3e34fc8662ff63e5e408d358234e8df5fd907f2` correctamente:

- JavaScript: 35 pruebas aprobadas.
- Windows: 10 pruebas Rust aprobadas, compilacion release y empaquetado NSIS/MSI.
- Android local: siete pruebas Java aprobadas, APK compilado y firma verificada.
- Subida real y borrado local en Android: confirmados por el usuario en su telefono.
- OAuth, subida real y ejecucion del instalador en Windows: pendientes de prueba.

Los instaladores descargados quedan en `artifacts/windows/` (excluido de Git):

- `nsis/Mori_4.4.0_x64-setup.exe`
- `msi/Mori_4.4.0_x64_en-US.msi`

El APK de pruebas Android esta en
`android/app/build/outputs/apk/debug/Mori v4.4.0.apk`.
