# BastionMCShop 1.0.0-beta1

Primera version funcional para probar en **Purpur 1.21.11 / Java 21**, con **Vault + proveedor de economia (EssentialsX Economy)**.

## Instalacion
1. Sube todo el contenido de este ZIP a la raiz de un repositorio de GitHub (incluida la carpeta oculta `.github`).
2. Ve a Actions y ejecuta **Build BastionMCShop**.
3. Descarga el artifact `BastionMCShop-JAR`, descomprimelo y coloca el `.jar` en `plugins/`.
4. Reinicia. Vault y EssentialsX Economy deben estar instalados y funcionando.

## Comandos
- `/shop`: categorias, paginas, productos, compra y venta por 1/16/32/stack maximo.
- `/sellall`: abre confirmacion con vista previa.
- `/sellall fast`: vende sin confirmacion.
- `/bastionshop reload`: recarga configuraciones.

## Configuracion
- `plugins/BastionMCShop/config.yml`: porcentaje de venta, textos, menus, permisos de funciones.
- `plugins/BastionMCShop/catalog.yml`: categorias, productos, precios y blacklist.
- Precios de catalogo son **ejemplos iniciales**, no un balance final. `sell-percentage: 50`.
- Cada producto puede declararse como `IRON_INGOT: 9` o:
  ```yaml
  IRON_INGOT:
    buy-price: 9
    sell-price: 4.5
  ```
- Colores MiniMessage (`<gradient:#00C135:#17FF00>`), HEX `&#00C135`, `&l`.
- La lista negra incluye spawners, cabezas y libros, incluidos libros encantados y escritos.
- El sistema solo vende objetos de catalogo y evita objetos con nombre, encantamientos, lore, custom model data o PDC en `/sellall` y venta manual.

## Limitaciones de esta beta
- Catalogo inicial representativo de 8 categorias: **no incluye todavia absolutamente todas las variantes de madera/piedra**. Se pueden agregar desde YAML.
- No incluye calculo automatico de recetas ni analisis global de arbitraje. Revisa los precios antes de abrir la tienda al publico.
- No soporta por ahora productos con componentes NBT especiales o variaciones complejas.
- Venta en dos decimales; valores extremadamente bajos pueden redondearse a cero.
- No se ha compilado ni probado en servidor real desde este entorno. Probar en un servidor de pruebas y respaldar antes de instalar.


## v1.0.1 beta - UI
Menus de 54 espacios con borde y separacion: 8 productos por pagina, cabeza real del jugador en slot 49 con saldo Vault, botones separados de compra/venta, pagina y flechas. Configurar paneles en config.yml bajo shop.design. La version no modifica la logica de transacciones.


## v1.0.2 - Menus personalizables
La configuracion de slots, paneles, titulos, nombres y lore esta en `config.yml` bajo `shop.layout` y `shop.texts`.
Las categorias muestran 8 botones separados, los productos usan hasta 28 espacios por pagina y la cabeza del jugador muestra saldo Vault.
IMPORTANTE: los YAML existentes NO se sobrescriben automaticamente; copia `shop.layout` y `shop.texts` del ZIP al config del servidor.
Evita colocar slots de productos, botones y cabeza en el mismo lugar. La pagina se calcula automaticamente.


## v1.0.3 - menus.yml
- `plugins/BastionMCShop/menus.yml` controla títulos, textos, materiales, paneles y posiciones.
- Menú principal de 45 slots, 8 categorías centradas, sin flechas; cabeza del jugador en slot 44.
- Categorías de 54 slots, 28 productos por página y botones de navegación.
- `shop.layout.root.slots` ordena las 8 categorías según el orden en `catalog.yml`.
- `shop.category-slots.<clave>` permite asignar posiciones de productos de una categoría.
- `shop.categories.<clave>.material`, `.name`, `.lore` personalizan el icono de la categoría.
- `/bastionshop reload` vuelve a leer config.yml, menus.yml y catalog.yml.
- IMPORTANTE: para servidores que ya tengan BastionMCShop instalado, `menus.yml` se creará automáticamente, pero `catalog.yml` existente NO se sobrescribe. Si deseas los productos nuevos, combina las entradas del catálogo del ZIP con el tuyo.
- Precios de catálogo ampliado son ejemplos que deben revisarse antes de abrir al público.
