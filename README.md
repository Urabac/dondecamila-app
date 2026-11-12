# Panel Dondecamila (app Android)

App que abre el panel `dondecamila.vercel.app/admin` e imprime los tickets
**directo por Bluetooth** en la impresora térmica (602-R58D), en modo texto, sin RawBT.

## Cómo obtener el APK
1. Cada vez que se sube un cambio a `main`, GitHub compila la app sola.
2. Ve a la pestaña **Actions** → la ejecución más reciente con ✅ → abajo, en **Artifacts**,
   descarga **Panel-Dondecamila** (es un .zip; adentro está `Panel-Dondecamila.apk`).
3. Para compilar sin cambios: Actions → **Construir APK** → **Run workflow**.

## Notas
- El panel se carga desde internet: los cambios del panel en el otro repo se ven en la app sin reinstalar.
- Solo hay que reinstalar el APK si se cambia algo de esta app (impresión, permisos, ícono).
- La firma (`app/dondecamila.keystore`) es fija para que las versiones nuevas se instalen encima. No la borres.
