# AirPods standalone — prova fisica da eseguire

Stato iniziale: **NON ESEGUITA / H1 NV**. Questo documento rende verificabile il
gate F1+C6, non attribuisce un PASS e non rende il telefono disponibile. Il
telefono SM-S931B e le cuffie sono indisponibili per la notte per istruzione
dell'utente del 2026-11-08. Nessuna operazione device è autorizzata prima della
restituzione coordinata, prevista da domani pomeriggio. L'assegnazione precedente
e l'inventario Device su host local sono dati storici, non disponibilità attuale.
Nessuna prova del nuovo incarico è stata ancora eseguita: il candidato deve prima
superare correzioni e review software. Il tablet
resta assegnato a FluidWeather e non è una risorsa di questa chat.
G1/CP1.b storico di convivenza resta NV; questa prova usa il nuovo requisito
esclusivo, senza cambiare LibrePods o le sue impostazioni.

## Prerequisiti prima di una prova autorizzata

- Congelare software e APK dopo integrazione e review; registrare SHA256, firma,
  versionName/code, modello telefono, build Android, firmware e identità AirPods.
  Gli APK di checkpoint in percorsi build normali sono mutabili.
- Verificare che questa chat possieda le risorse device/build usate. Per controllo
  UI usare il pannello Dispositivo e i suoi strumenti; non chiudere altre sessioni.
- Chiedere all'utente la disponibilità delle cuffie indossate per audio/ear/CA.
  Gestione esclusiva: nessun altro client AACP attivo. Chiusura foreground di LP
  non dimostra che il suo canale background sia chiuso; non forzare/riconfigurare
  l'app ufficiale. Se manca una baseline esclusiva osservabile, mantenere NV.
- Il prodotto rimane conformance=false. Usare l'ingresso debug non esportato,
  scelta visibile della coppia già abbinata e consenso temporaneo scratch.
  Nessun Intent, restore o associazione può simulare il consenso o un PASS.
- L'utente accorda i permessi normali richiesti con azioni visibili. Background,
  automazioni e altre app sono consensi separati OFF di default. NLS non dà
  certezza sulla route esterna: il consenso C6 deve descrivere questa inferenza.
- Nessuna chiamata reale per testare i gesti senza cooperazione esplicita
  dell'utente su una chiamata di prova. Non avviare o terminare chiamate estranee.

## Evidenza per ogni sessione S1, S2, S3

Ogni sessione è un'apertura indipendente con chiusura finale e normale riconnessione
delle cuffie. Registrare tempi monotoni e timestamp, attore/UID/socket, TX separati
da RX, proprietà e generazione; nessun dato personale in report condivisibili.
Lo screenshot di un valore o il ritorno true di una richiesta non è un readback.

1. Baseline: route audio e playback locale o altra app, modello/firmware, modalità
   e CA iniziali osservate, volume. Un solo owner Fluidify. Nessun loop di retry.
2. Costruzione → connessione BR/EDR PSM0x1001 → handshake → sottoscrizione, ciascuno
   distinto. Deadline connect10s; cancellazione chiude il socket. Se un passaggio
   fallisce, registrare il negativo e gli scenari successivi NV.
3. INFORMATION corrente e batterie per componente nullable con provenienza/età.
   Identità incompleta resta unknown. La tabella modello è modelDeclared, non RX
   capability dedicata; conferma una proprietà solo il suo RX/readback corrente.
4. Tre cambi per ogni modalità applicabile e CA ON/OFF con RX entro5s; ascolto
   confermato dall'utente distinto dal log. Non derivare supporto da TX/pending.
5. Per tutte le 35 famiglie del catalogo incluso/adattato: annotare applicabilità
   motivata, controllo provato, RX/readback/effetto atteso, risultato e limite.
   Nessun controllo di una famiglia certifica automaticamente gli altri. La
   matrice iniziale è in build/airpods-full-integration/validation/hardware-ledger.json.
   Verificare range, multi-byte, opzioni collegate, nomi UTF-8, informazioni complete,
   EQ, tracking standard/alternativo stop→start senza takeover, steli e Tile.
6. Controlli audio e AACP separati: richiesta connect/disconnect ordinaria può
   essere accettata senza completamento. Conferma da stato profilo reale. Se il
   metodo non è accessibile, fallback Settings solo da azione visibile esplicita;
   nessuna apertura UI da unworn o callback tardiva. Conservare il motivo.
7. Gesti chiamata con permessi/consenso: nod risposta e shake rifiuto sulla chiamata
   designata, nessuna azione media durante chiamata. Revoca/stop/cambio sessione e
   chiamata finita invalidano campioni e azioni in coda. Diniego non equivale a
   fallimento di una chiamata effettuata né a successo della funzione.

## Sequenze delle automazioni

Ripetere tre sequenze ear e tre conversazioni complete. Valori attesi seguono C5:
primo campione baseline senza effetto; stereo→mono/nessuno e mono→nessuno pausano
solo un target PLAYING valido; nessuno→mono/stereo e mono→stereo rilasciano il
motivo ear, con ripresa solo della pausa posseduta e senza altri motivi. L↔R,
duplicati e unknown non inventano un reinserimento. Provare ear+CA in entrambi
gli ordini. Fine conversazione non rimuove il motivo ear e viceversa.

Provare player Fluidify Spotify, file/video e altra app con NLS+C6 separatamente:
volume manuale, PAUSE manuale già PAUSED quando osservabile, PLAY manuale, cambio
app, route, account/logout, focus/altro duck. Nessuna seconda attenuazione, ripresa
su target nuovo o rollback nel cleanup. Una notifica Settings con livello invariato
non crea ownership; divergenza osservata revoca quella precedente. Intenti non
osservabili restano un limite esplicito, non una conferma causale.

## Background e chiusura

Armare esplicitamente10–15min nella corsia di prova. Il collaudo **non acquisisce
un wake lock aggiuntivo**: stessa disponibilità di risorse della produzione.
Includere almeno10min con schermo spento e altra app attiva, senza playback di
Fluidify e con pausa oltre90s. Verificare comandi/eventi freschi dopo il silenzio.
Il permesso scade per tempo monotono; il timer non prova un risveglio OS né una
chiusura esatta durante deep sleep. Tornare al pannello e arrestare la prova.

Provare stop interno/notifica, disabilitazione, permesso revocato, bond perso,
Task Manager/force-stop e ricreazione secondo C4, senza confonderli. Osservare
cleanup reale e altri2min senza riapertura autonoma. Stop di sistema senza callback
richiede exit history/suppress prima di ogni nuovo ingresso automatico; aprire
l'app o un bind non sostituisce Riattiva esplicito.

## Verdetto

PASS solo con evidenze versionate sufficienti per tutti gli scenari applicabili;
FAIL per difetto riproducibile introdotto dal client; NV per assenza/insufficienza
di dati o baseline. NA richiede motivo concreto della piattaforma/modello, non
assenza di un'implementazione. Campo mancante resta NV. Test JVM, fixture e
richieste piattaforma accettate non diventano PASS fisico. L'abilitazione prodotto
non cambia prima del gate richiesto; nessun successo storico viene trasferito.
