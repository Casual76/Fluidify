# Fluidify per Wear OS — checklist di prova sul dispositivo

Per il giro di prove sul Galaxy Watch dopo le fasi 2 e 3. Ogni voce dice cosa fare e cosa
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
| Trio in basso (uscita, coda, cuore) | coda 58 dp (52 dp compact), uscita e cuore 50/48 dp; nessuna sovrapposizione, vetro che risponde alla pressione |
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
| Telefono che comanda il PC senza notifica media attiva | compare la nostra icona; tocco = lettore. Il permesso notifiche negato da solo non implica che i controlli media siano assenti |
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

## Fase 3 — da eseguire sul Galaxy Watch e sul telefono

Le spunte seguenti riguardano prove reali. I render JVM e i test automatici sono documentati
separatamente in `wear-phase3-progress.md`. Il 4 ottobre 2026 ADB non vedeva dispositivi collegati:
nessuna di queste prove viene dichiarata superata soltanto perché compila.

### Lettore, like e playlist

- [ ] Lettore a 192/216/240 dp: coda più grande, tasti interi e nessun tocco intercettato da un vicino.
- [ ] Font al 130%, titolo lungo e avviso di connessione: sparisce prima la seconda riga; titolo leggibile, dischi almeno 48 dp e separati.
- [ ] Cuore vuoto → like immediato; cuore pieno → conferma. Annulla mantiene il like, conferma lo rimuove sul telefono.
- [ ] Stessa conferma dal tocco lungo sulla copertina immersiva e dal cuore pieno della tile, anche con processo dell'app ricreato.
- [ ] Tocco lungo sul cuore e voce Essenziali → solo playlist modificabili; aggiunta visibile sul telefono, conferma e ritorno al lettore.
- [ ] Errore LIKE/PLAYLIST → avviso comprensibile; playlist seguite e destinazioni di modificabilità ignota assenti.
- [ ] Ghiera nel lettore e sulla copertina → volume, disco di vetro grande, percentuale e dispositivo su una riga.
- [ ] Primo scatto prima di conoscere il volume → nessun comando. Telefono lontano → nessuna raffica di vibrazioni o errori.
- [ ] Modifica del volume dal telefono, dal sistema Watch e da Connect; poi ghiera → valore reale, nessun salto o comando soppresso.
- [ ] Cambio PHONE/WATCH/dispositivo mentre si gira la ghiera → nessun comando tardivo destinato all'uscita precedente.
- [ ] Play/pausa rapido → l'ack anticipato non fa rimbalzare l'icona; errore restituisce lo stato reale.
- [ ] Doppio tocco rapido su Coda/Uscita/Essenziali/playlist → una sola schermata da chiudere.
- [ ] Locale RTL → indietro a sinistra e avanti a destra.

### Handoff, servizio e ponte

- [ ] "Continua sull'orologio" dal telefono con Watch a schermo spento → riproduzione e controlli funzionanti.
- [ ] Primo avvio lento o fallito del motore → avviso, ritorno PHONE, secondo tentativo possibile.
- [ ] WATCH già attivo, cambio cuffie/altoparlante → cambia solo l'uscita senza rifare l'handoff.
- [ ] Telefono lontano, apertura di una playlist Watch → parte quella scelta, senza riprendere sopra la musica del telefono.
- [ ] Ritorno al telefono riuscito → pausa Watch solo dopo trasferimento confermato. Ritorno fallito → resta WATCH.
- [ ] Cuffie staccate dopo musica arrivata via Connect, altoparlante vietato → pausa senza suono dall'altoparlante.
- [ ] Watch in pausa per due minuti → motore, richiesta Wi-Fi e dispositivo Connect spariscono; Riprendi ricostruisce la sessione.
- [ ] Logout sul telefono durante playback Watch → pausa, motore fermo, modalità PHONE e vecchio account cancellato.
- [ ] Telefono esce/rientra nel raggio Bluetooth → stato collegamento e copertine si aggiornano senza riaprire l'app.
- [ ] Telefono ucciso durante un brano, Watch riavviato dopo la durata residua → stato in pausa, niente "in riproduzione" eterno.
- [ ] Home a freddo in caricamento, premere Pausa → effetto immediato; Home risponde o mostra la cache nel budget.
- [ ] Telefono con servizio spento: Play, playlist, coda e like → motore svegliato e ack entro il budget esteso.
- [ ] Sleep "a fine brano" in WATCH → pausa al cambio/fine, senza cancellare silenziosamente il timer.
- [ ] Telefono Android 8–10: miniature e verifica APK non usano API non disponibili.

### Download, aggiornamenti e installatore

- [ ] Interrompere Bluetooth a metà paginazione di una playlist già scaricata → nessun brano precedente perso.
- [ ] Playlist svuotata sul telefono, sincronizzazione completa → rimuove correttamente i brani non più presenti.
- [ ] Interrompere un file, cambiare qualità e riprendere → nuova identità riparte da zero, nessun file mescolato.
- [ ] Canale aperto senza byte per 30 secondi → chiusura, ripresa controllata; annullare il worker libera la rete.
- [ ] Brano rimosso/non disponibile → dopo tre passate saltato e conteggio visibile. Interruzioni di rete non aumentano quel conteggio.
- [ ] Avviare download e playback insieme, annullarne uno → la richiesta Wi-Fi dell'altro resta attiva; terminare entrambi la libera.
- [ ] Rotazione del telefono durante installazione ADB → nessuna seconda installazione. App in background → discovery/multicast fermati.
- [ ] Offerta aggiornamento, riavviare processo telefono → invio ancora possibile. Offerta scaduta → Watch non resta su "accettato".
- [ ] APK telefono, hash assente/errato o firma diversa → rifiuto. APK Wear valido → download persistente e installazione.
- [ ] Download APK interrotto → KEY_CHECKED non impedisce il tentativo successivo.

### Tile, ambient e accessibilità

- [ ] Tile a 192/240 dp, progresso 0/40/80/100% → due archi liberi da titolo e cuore, tasti completamente visibili.
- [ ] Comando tile fallito → ridisegno con stato reale. Richiesta che ripete un vecchio nonce → nessun secondo salto, anche dopo riavvio processo.
- [ ] Playback WATCH: copertina arriva a tile/complicazione; aggiornamenti del telefono non sostituiscono quella Watch.
- [ ] Ripeti uno o seek indietro → icona ongoing resta valida mentre il brano suona.
- [ ] Playlist con brani duplicati, Home/Ricerca con URI ripetuti → nessun crash per chiavi duplicate.
- [ ] Ritorno da risultato di ricerca → query e risultati ripristinati. Ritorni da Coda/Uscita → Home non ricaricata inutilmente.
- [ ] Fissa/togli una playlist presente in più scaffali → tutte le righe aggiornate; vibrazione dopo successo, avviso sul fallimento.
- [ ] Ambient da lettore/copertina/Home/Coda/Altro → fondo nero, ora/titolo/artista, niente copertina a pieno schermo; low-bit senza sfumature.
- [ ] App in background → anelli non animati. Uscita dall'ambient → schermata e controlli corretti.
- [ ] TalkBack: nomi volume ±, annuncio percentuale, azione Fissa/Togli e azioni play/pausa/avanti/mi piace sulla copertina.
- [ ] Permesso notifiche cambiato nelle impostazioni → Altro aggiornato al ritorno; rifiuto permanente apre le impostazioni.
- [ ] Collegare cuffie mentre Now bar sperimentale attiva → le due notifiche non si sostituiscono.

## Patch 1.5.1 — Bluetooth, aggiornamenti, AOD

- [ ] Con telefono vicino, usare il lettore per 15 minuti: nessuna richiesta Wi-Fi attiva di Fluidify in `dumpsys connectivity`; distinguere le registrazioni passive dalle richieste di rete.
- [ ] Riprodurre download locali: nessuna attivazione Wi-Fi richiesta dall’app; dopo il ritorno al telefono motore e servizio locali si fermano.
- [ ] Scaricare un brano non presente sul telefono o a qualità diversa: staging sul telefono e trasferimento Bluetooth prima del ripiego Wi-Fi.
- [ ] Interrompere Bluetooth durante un download: l’eventuale richiesta Wi-Fi finisce con il worker; tornando al telefono non resta una richiesta attiva.
- [ ] Dopo aver ascoltato sull’orologio, avviare la musica sul telefono/in auto: titolo, coda e controlli tornano al telefono senza aspettare due minuti.
- [ ] Un handoff esplicito all’orologio resta locale: un vecchio snapshot o un Connect remoto che rappresenta l’orologio non lo annullano.
- [ ] Inviare un update con schermo del telefono e dell’orologio spenti: progressi reali e notifiche; controllo della ricezione finale.
- [ ] Con installazione che richiede conferma, lasciare spegnere lo schermo: riaprire la notifica o Altro; la conferma è recuperabile senza un nuovo invio.
- [ ] Annullare/bloccare l’installazione, poi ritentare: l’APK su Wear resta identico e il contatore dei byte Bluetooth non cresce di altri ~24 MB.
- [ ] Ricreare i processi prima della conferma: stato e azione restano disponibili; il telefono recupera lo stato sul successivo hello.
- [ ] AOD da player, copertina e altre schermate: dissolvenza breve, titolo nella parte alta; nessun rendering interattivo continuo dopo la transizione. Provare anche font 130% e pannello low-bit.
- [ ] Misurare il consumo in sessioni comparabili con 1.5.0/1.5.1: i test JVM non dimostrano una riduzione della batteria sul Galaxy Watch.
