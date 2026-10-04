# Render della fase 3

33 PNG generati con `:wear:recordRoborazziDebug`, Java 21 e Robolectric API 36/HWUI.
Le schermate sono circolari a densità 2: 192/216/240 dp corrispondono a 384/432/480 pixel.
La copertina è sintetica; orari e posizione dinamica dipendono dal momento della cattura.

| Caso | Render |
|---|---|
| Lettore 240 / 216 / 192 dp | `player_playing.png`, `player_playing_40mm.png`, `player_playing_192dp.png` |
| Font 130%, riga di stato | `player_192dp_font130_status.png`, `player_216dp_font130_status.png`, `player_240dp_font130_status.png` |
| Volume grande | `player_volume.png`, `volume.png` |
| Ambient nero | `player_ambient.png` |
| Conferma unlike | `unlike_dialog.png` |
| Playlist modificabili | `add_to_playlist.png` |
| Tile 192 dp, primo/secondo arco/piena | `tile_playing_192dp.png`, `tile_192dp_late.png`, `tile_192dp_full.png` |
| Tile 240 dp, primo/secondo arco/piena | `tile_playing.png`, `tile_playing_late.png`, `tile_240dp_full.png` |

Il render 192 dp con font grandi è stato corretto dopo aver osservato un titolo tagliato in altezza.
I test verificano la separazione dei tasti di trasporto e della coda; la review dei PNG verifica
il varco dell'anello attorno al cuore. La conferma mostra i due tasti Wear M3 e il titolo del brano.

Queste immagini non verificano Bluetooth, audio reale, consumo, gesti fisici, TalkBack o Samsung Now bar.
Usare la checklist in `docs/wear-qa-checklist.md` per quelle prove.
