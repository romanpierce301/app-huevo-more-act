# Variantes de la app (Product Flavors)

El código base vive en `app/src/main`. Cada variante (marca) es un **flavor** de Gradle
que solo sobrescribe branding (nombre, slogan, colores, logos) sin tocar el código compartido.
Así, cuando se actualiza el código base, **todas las variantes se actualizan automáticamente**.

## Estructura

- `app/src/main` → código y recursos comunes (base).
- `app/src/hyhhuevos` → variante base (H y H HUEVOS). Usa los recursos de `main`.
- `app/src/lili` → variante "Distribuidora de Huevos LILI".
  - `java/com/example/brand/Brand.kt` → nombre, slogan y colores de la marca.
  - `res/values/strings.xml` → `app_name` y `brand_slogan`.
  - `res/drawable/img_ticket_logo.png` → logo usado en tickets y PDFs.

## Compilar una variante

```
./gradlew assembleHyhhuevosDebug
./gradlew assembleLiliDebug
```

Cada variante tiene su propio `applicationId`, por lo que se pueden instalar juntas.

## Dónde poner los logos

- Ticket / PDF: `app/src/<variante>/res/drawable/img_ticket_logo.png`
- Pantalla de bienvenida: `app/src/<variante>/res/drawable/img_welcome_hero.png`
  (la bienvenida usa ese drawable; reemplázalo con tu logo a color si lo deseas)
- Ícono de la app (launcher): `app/src/<variante>/res/mipmap-* / ic_launcher*.png`
  (para un ícono adaptativo, también `mipmap-anydpi-v26/ic_launcher.xml` y `drawable/ic_launcher_foreground.png`)

## Crear una nueva variante

1. Copia la carpeta `app/src/lili` a `app/src/<nueva>`.
2. Edita `Brand.kt` (nombre, slogan, colores).
3. Edita `res/values/strings.xml`.
4. Reemplaza los logos.
5. Añade el flavor en `app/build.gradle.kts`:

```kotlin
create("<nueva>") {
    applicationId = "com.aistudio.hyhhuevos.<nueva>"
}
```

## Notas

- El color principal de cada variante se define en `Brand.primaryColor` y se aplica
  automáticamente a toda la UI (Color.kt lee de Brand).
- El código compartido nunca debe hardcodear el nombre de la marca; usa
  `Brand.APP_NAME` / `Brand.SLOGAN` o `R.string.app_name` / `R.string.brand_slogan`.
