# Fluidify per Wear OS — checklist di prova sul dispositivo

Per il giro di prove sul Galaxy Watch dopo la fase 2 (rifinitura). Ogni voce dice cosa fare e cosa
deve succedere; accanto, cosa guardare se non succede. Build consigliata: `:wear:assembleDev` e
`:app:assembleDev` (non debuggable, ottimizzate come la release ma senza R8), firmate con la stessa
chiave.

Prima di cominciare, sul Watch: se il contatore del vetro era rimasto acceso, sparisce da solo (la
chiave è nuova); "Notifiche del telefono" ora sta nelle opzioni sviluppatore e torna a valere al
prossimo avvio dell'app.

## Lettore

| Prova | Atteso |
|---|---|
| Tasto centrale in basso | si apre la **coda** (non la Home), col brano in riproduzione evidenziato e "Prossimi" sotto |
| Trio in basso (uscita, coda, cuore) | dischi da ~50 dp, facili da centrare; nessuna scintilla alla pressione, solo il vetro che si gonfia e si accende dove tocchi |
| Capsula del titolo e dischi | stesso vetro (velo scuro, filo chiaro), nessuna differenza di fondo fra i due |
| Avanzamento | linea sul bordo vero dello schermo, alone che cresce verso l'interno, testa luminosa; varco in alto attorno all'ora, che sta in una capsula di vetro |
| Colore | anello, cuore acceso e vetro prendono il colore della copertina, e cambiano col brano |
| Ghiera | arco del volume sul bordo destro + scheda al centro con percentuale e dispositivo; i controlli si attenuano |
| Avanti | titolo e copertina del brano successivo compaiono subito, prima della conferma del telefono |
| Copertina immersiva (swipe giù) | tocco = play/pausa, doppio tocco = avanti, tocco lungo = mi piace; ogni volta un disco di vetro col segno al centro |
| Swipe fra le pagine | transizione di Wear (la pagina che esce si rimpicciolisce sotto un velo), nessun arco disegnato sopra la Home |

## Now bar

| Prova | Atteso |
|---|---|
| Musica sul telefono, notifiche del telefono attive | sul quadrante **un solo** Fluidify: quello di sistema. Il nostro non compare |
| Telefono che comanda il PC senza notifica, oppure notifiche del telefono negate | compare la nostra icona; tocco = lettore |
| Altro → "Fluidify sul quadrante" | Automatica → Sempre → Mai, a ogni tocco |
| Opzioni sviluppatore (7 tocchi su Informazioni) → "Controlli Fluidify sul quadrante" | **esperimento**: dire se il controller Samsung sparisce e se la voce media nella Now bar è la nostra (tocco = lettore, tasti funzionanti). Se sì, diventa il comportamento predefinito |

## Funzioni che nel QA erano rotte

| Prova | Atteso | Se non va, nei log |
|---|---|---|
| Uscita → Cuffie/Altoparlante con il telefono **in riproduzione** | "Passo all'orologio…", poi la musica continua sull'orologio dallo stesso punto, il telefono si ferma | `ActivePlayback`: quale delle tre strade ha funzionato; sul telefono `RemoteConnect` |
| "Continua sull'orologio" dal telefono, orologio a schermo spento | come sopra | idem |
| Radio dal brano, in riproduzione sull'orologio | parte la radio sull'orologio; se non può, un avviso al centro | `LocalControls` |
| Qualunque comando che fallisce (telefono spento, ecc.) | avviso leggibile al centro dello schermo, con vibrazione | — |
| Installazione da zero dal telefono | il telefono trova da solo indirizzo di accoppiamento **e** di connessione; se no, il secondo campo accetta l'indirizzo della schermata Debug wireless | `WatchInstaller` |
| Installazione senza release pubblicata | messaggio "Non c'è ancora una versione per orologio pubblicata"; "Usa un APK su questo telefono" la completa | — |
| Relay Bluetooth interrotto (Wi-Fi/BT del telefono spenti 10 s) | il download riprende da dove era nella stessa passata | `PhoneFileTransfer`: "resuming …, attempt N" |
| Nome in Spotify Connect | "Galaxy Watch7", non "SM-L310" | — |

## Home e libreria

| Prova | Atteso |
|---|---|
| Home | in cima le playlist fissate (puntina), poi quelle ascoltate di recente, poi "Creati per te" con Discover Weekly/Release Radar/Daily Mix, poi gli scaffali di Spotify |
| Copertine | tutte presenti dopo pochi secondi, anche quelle delle playlist generate; restano col telefono lontano |
| Tocco lungo su una riga | fissa/toglie, anche sul telefono |
| Swipe giù dal lettore alla Home | nessuno scatto; con `adb logcat -s FluidifyFrames` nessuna riga "swipe home" sopra il doppio del budget |

## Tile e complicazione

| Prova | Atteso |
|---|---|
| Avanti/indietro dalla tile, 10 volte di fila | ogni pressione cambia brano e la tile mostra subito quello nuovo |
| Grafica | copertina sfocata dietro, tasti traslucidi, anello di avanzamento sul bordo che si muove da solo, varco per il titolo |
| Riproduzione sull'orologio | tile e complicazione seguono il player dell'orologio |
| Complicazione "valore a intervallo" su un quadrante che la supporta | barra di avanzamento che si muove durante il brano |

## Engine

`engine-2.11.0` è taggato in locale sul commit `release: engine 2.11.0` del ramo
`claude/sweet-ride-gmzu9g` del Fluid Engine: il push del tag dal container non passa, va fatto a
mano (`git push origin engine-2.11.0`), come per 2.10.x.
