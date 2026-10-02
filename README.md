# Procedural Bites

[Español](#español)

A client-side Minecraft mod that animates eating and drinking. While you eat, the food loses bites; while you drink, the liquid level goes down. The frames are generated from each item's own texture, so it works with vanilla, with food mods and with resource packs, without hand-drawn frames.

## What it does

- **Solid food** gets three bites. Fruit with a stem is eaten down to the core; long food is eaten from the tip.
- **Drinks** in bottles, buckets, glasses and cups drain row by row. Labels and garnish stay where they are; corks come off.
- **Bowls**: the food sinks into the bowl and the empty bowl is left at the end. This includes bowls that mods draw with their own art.
- **Potions** drain inside the bottle and keep their color.
- **Resource packs**: the frames are generated from the active texture, so they match the pack.

Items that can't be animated keep their normal model: 3D or block models, animated textures, textures larger than 256x256, and drinks whose liquid isn't visible.

## Requirements

- Minecraft 1.21.1 with NeoForge 21.1.252 or newer.
- Client only. Servers don't need it, and you can join servers that don't have it.

## Configuration

In the mod list (Mods, Procedural Bites, Config) or in `config/proceduralbites-client.toml`:

- `solids`, `drinks`, `bowls`, `potions`: turn each kind of animation on or off.
- `excluded`: item ids without animation. `*` matches any text, for example `minecraft:*_stew`.
- `cache`: keep the generated frames in `.cache/proceduralbites` so the game loads faster next time.

Changes apply when resources reload (F3+T).

## Hand-drawn frames and Eating Animation

If an item's model already has eating frames, Procedural Bites leaves it alone and only animates the rest. It recognizes `overrides` on these model properties:

- `minecraft:eat` and `minecraft:eating` (Eating Animation for Fabric and the packs made for it)
- `eatinganimation:eat` and `eatinganimation:eating` (the NeoForge port of Eating Animation)
- `proceduralbites:eat_progress`, from 0 (not started) to 1 (finished)

When Eating Animation isn't installed, Procedural Bites registers its properties itself, so packs made for it still work.

## Hints for mods and packs

Some textures don't say enough on their own. A yogurt cup seen from above looks like a cake, for example. A mod or a resource pack can add `assets/<namespace>/proceduralbites/hints.json`:

```json
{
  "top_cup": ["mymod:*_yogurt", "mymod:tea"],
  "none": ["mymod:mystery_juice"],
  "solid": ["mymod:pie_in_a_bowl"],
  "auto": ["othermod:soup"]
}
```

- `top_cup`: a cup, bucket or glass seen from above. The surface goes down.
- `none`: no animation.
- `solid`: bitten, even if the texture looks like a container.
- `auto`: clears a hint coming from a pack or mod further down.

`*` matches any text. Packs higher in the resource pack list win. The mod already ships hints for Pam's HarvestCraft 2 and Croptopia.

## Tested with

Farmer's Delight, Croptopia, Let's Do (Bakery, Farm & Charm, Vinery, HerbalBrews), Pam's HarvestCraft 2 (Food Core and Food Extended), Faithful 32x, and the NeoForge port of Eating Animation. That's more than 1600 foods and drinks. If something looks wrong with another mod, please open an issue with the mod name and the item.

## Building

JDK 21 is needed.

```
./gradlew build
```

The jar is in `neoforge-1.21.1/build/libs/`. The algorithm lives in `core/`, plain Java 17 with no Minecraft dependencies. `./gradlew :core:test` runs its tests.

## License

LGPL-3.0-only. See [COPYING.LESSER](COPYING.LESSER) and [COPYING](COPYING).

The mod doesn't include any texture from Minecraft or from other mods. Every frame is generated on the player's computer from the textures they already have.

## Español

Mod de Minecraft, solo de cliente, que anima la comida y la bebida. Mientras se come, la comida pierde mordidas; mientras se bebe, baja el nivel del líquido. Los fotogramas se generan a partir de la textura de cada ítem, así que funciona con vanilla, con mods de comida y con paquetes de recursos, sin fotogramas dibujados a mano.

### Qué hace

- **Comida sólida:** tres mordidas. La fruta con tallo se come hasta el corazón y la comida alargada, desde la punta.
- **Bebidas** en botellas, baldes, vasos y tazas: el nivel baja fila por fila. Las etiquetas y los adornos quedan en su lugar; el corcho se quita.
- **Tazones:** la comida baja dentro del tazón y al final queda el tazón vacío, también con los tazones que los mods dibujan a su manera.
- **Pociones:** el líquido baja dentro de la botella y conserva su color.
- **Paquetes de recursos:** los fotogramas salen de la textura activa, así que combinan con el paquete.

Lo que no se puede animar queda con su modelo normal: modelos 3D o de bloque, texturas animadas, texturas de más de 256x256 y bebidas que no dejan ver el líquido.

### Requisitos

- Minecraft 1.21.1 con NeoForge 21.1.252 o más nuevo.
- Solo cliente. El servidor no lo necesita y se puede entrar a servidores que no lo tienen.

### Configuración

Desde la lista de mods (Mods, Procedural Bites, Config) o en `config/proceduralbites-client.toml`:

- `solids`, `drinks`, `bowls`, `potions`: activa o desactiva cada tipo de animación.
- `excluded`: ids de ítems sin animación. `*` vale por cualquier texto, por ejemplo `minecraft:*_stew`.
- `cache`: guarda los fotogramas generados en `.cache/proceduralbites` para cargar más rápido la próxima vez.

Los cambios se aplican al recargar los recursos (F3+T).

### Fotogramas dibujados a mano y Eating Animation

Si el modelo de un ítem ya trae fotogramas de comer, Procedural Bites lo respeta y solo anima el resto. Reconoce los `overrides` sobre estas propiedades de modelo:

- `minecraft:eat` y `minecraft:eating` (Eating Animation para Fabric y los paquetes hechos para él)
- `eatinganimation:eat` y `eatinganimation:eating` (la versión de Eating Animation para NeoForge)
- `proceduralbites:eat_progress`, de 0 (sin empezar) a 1 (terminado)

Si Eating Animation no está instalado, Procedural Bites registra esas propiedades, así que los paquetes hechos para él siguen funcionando.

### Pistas para mods y paquetes

Hay texturas que no alcanzan a decir qué son: un vasito de yogur visto desde arriba se parece a un pastel. Un mod o un paquete de recursos puede agregar `assets/<espacio>/proceduralbites/hints.json` con el formato del ejemplo de arriba:

- `top_cup`: taza, balde o vaso visto desde arriba. Baja la superficie.
- `none`: sin animación.
- `solid`: se muerde, aunque la textura parezca un recipiente.
- `auto`: anula una pista de un paquete o mod que está más abajo.

`*` vale por cualquier texto. Gana el paquete que está más arriba en la lista. El mod ya trae pistas para Pam's HarvestCraft 2 y Croptopia.

### Probado con

Farmer's Delight, Croptopia, Let's Do (Bakery, Farm & Charm, Vinery, HerbalBrews), Pam's HarvestCraft 2 (Food Core y Food Extended), Faithful 32x y la versión de Eating Animation para NeoForge: más de 1600 comidas y bebidas. Si algo se ve mal con otro mod, se puede abrir un issue con el nombre del mod y del ítem.

### Compilar

Hace falta JDK 21: `./gradlew build`. El jar queda en `neoforge-1.21.1/build/libs/`. El algoritmo está en `core/`, en Java 17 puro y sin dependencias de Minecraft; `./gradlew :core:test` corre sus pruebas.

### Licencia

LGPL-3.0-only. Ver [COPYING.LESSER](COPYING.LESSER) y [COPYING](COPYING). El mod no incluye texturas de Minecraft ni de otros mods: todos los fotogramas se generan en la computadora del jugador a partir de las texturas que ya tiene.
