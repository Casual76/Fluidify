# Review indipendente C — I2 stabile

Reviewer: C, agente di review indipendente, non autore. Modello effettivo GPT-6.1 Sol, reasoning high, harness Codex/T3. Fase IMPLEMENTAZIONE, giro CORRETTIVO2, versione I2 stabile. r2/r3 sono tentativi seriali di validazione, non ulteriori giri. Nessuna subdelega. Data: 2026-11-08.

Esito del focus: **richiede correzione**. Un difetto medio riprodotto nella composizione core/coda/coordinatore viola l'indipendenza tra pausa ear e pausa conversazione. I5 e I8 ricevono 7; questa review non soddisfa la soglia software. H1/G1 restano NV e Product FALSE.

## Perimetro e metodo

S = `C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I2-stable`. I riferimenti `source/...` e `evidence/...` seguenti sono relativi esclusivamente a S.

Letti agentic-workflow/SKILL.md e references/runtime.md, Fluid Engine e Material3 Expressive; applicata la rubrica comune senza attivare deleghe o le procedure build/device delle skill, vietate dal mandato. Nessun AGENTS.md trovato nei tre percorsi antenati controllati o nel congelato. Nessuna lettura di memoria/rollout, sorgenti live equivalenti, status live o review/voti altrui. Le sole scritture sono in questo scratch. Il report del serial validator r3 è evidenza parent autorizzata, non una review indipendente. Non consultato I1: nessuna conclusione propria sul delta storico o sull'attribuzione delle modifiche sporche.

Letti i fatti I2, rubric, contratti C1–C6, piano e coverage storici subordinati ai contratti attuali; esaminati catalogo, omissioni/esclusioni e fonte pinned pertinente alle notifiche CA (`catalog/sources/.../services/AirPodsService.kt:907`). Il mapping CA 1/2 e 6/8/9 del runtime coincide con tale sorgente. Questo campionamento non verifica integralmente tutte le 35 famiglie e 67 preferenze.

Scala comune: 9–10 implementazione forte con prove dirette e incertezza delimitata; 8 requisito soddisfatto nel perimetro software dichiarato con limiti testabili; 6–7 difetto/omissione o debolezza materiale; ≤5 violazione sostanziale o base insufficiente. Soglia ≥8 per ogni criterio numerico assegnato e nessun difetto grave confermato. NV non equivale a zero o approvazione. Non assegno voti alle parti non sufficientemente verificate.

## Rilievo C-01 — medio — UNKNOWN ear cancella una pausa appartenente solo a CA

**Trigger:** automazioni, ear pause/resume e conversation pause abilitate; target locale valido, stesso account/sessione/route, nessuna chiamata, override o revoca. Si stabilisce la baseline ear BOTH; il primo evento live CA=true accoda PAUSE. Un campione ear UNKNOWN arriva prima del dispatch oppure dopo PAUSED con ricevuta propria. Non esiste alcun motivo EAR: la pausa è stata acquisita esclusivamente da CONVERSATION.

**Sorgente:** `source/app/src/main/java/dev/lelonio/square/airpods/runtime/automation/AirPodsAutomationCoordinator.kt:164`–169. Il ramo UNKNOWN rimuove EAR ma poi cancella `pending` e azzera `ownedPause` indiscriminatamente. La cancellazione individuale è applicata dalla coda in `.../media/MediaRuntimeCore.kt:173`; la successiva fine CA raggiunge `release` a `AirPodsAutomationCoordinator.kt:216`, che non può più riprendere senza ownership.

**Prova eseguita personalmente:** compilato con javac ed eseguito `MediaProbe.java` in questo scratch, usando le classi `compiled` manifestate del pacchetto e Kotlin stdlib locale come dipendenza del runner. Il probe usa **MediaRuntimeCore e AirPodsAutomationCoordinator reali**, coda differita e provider fake con ricevuta persistente; non ricopia la policy. Output in `probe-output.txt`:

```text
Control CA start/end: [PAUSE, PLAY]
CA start, ear UNKNOWN, CA end: [PAUSE]
CA start, ear UNKNOWN before dispatch, CA end: []
```

Il controllo dimostra che la fixture dispone delle condizioni necessarie per riprendere. Le due varianti mostrano rispettivamente perdita della pausa posseduta e cancellazione della PAUSE CA ancora accodata. La corrispondente causa è visibile nel sorgente congelato. L'esecuzione riguarda le classi selezionate manifestate: non è una ricompilazione del progetto, prova di equivalenza APK/sorgente o di causalità fisica del provider.

**Conseguenza:** il dato ear sconosciuto impedisce la pausa conversazione richiesta o lascia il player fermo alla fine della conversazione, pur senza interventi manuali. C5 richiede motivi indipendenti e l'invalidazione dell'auto-resume *ear* su U; qui viene invalidata anche l'ownership esclusivamente CA. Il problema precede il bridge ed è comune ai target selezionati; riprodotto personalmente sul ramo locale, estensione al ramo C6 dedotta dal core condiviso.

**Fix minimo:** rendere l'invalidazione ear consapevole dei motivi/provenienza della pausa. UNKNOWN/cache/baseline ear non devono cancellare una richiesta o ricevuta appartenente esclusivamente a CA; preservare comunque il divieto di riprendere grazie a una sola ownership ear invalidata. Non aggiungere PLAY nel cleanup o attribuzione da semplice PAUSED.

**Regressione proporzionata:** aggiungere al test integrato del core i due interleaving sopra, sia locale sia C6, conservando le verifiche su permessi, route, revoca, override manuale e cleanup. Testare separatamente ownership solo EAR e overlap EAR+CA per non ampliare involontariamente l'autorità di ripresa. Il test esistente `AirPodsAutomationCoordinatorTest.kt:332`, `earUnknownDoesNotCancelIndependentConversationDuck`, copre il duck, non questa pausa CA; non falsifica il controesempio.

## Catena integrata ispezionata

- `AndroidAirPodsMediaRuntime` filtra la generazione, normalizza ear e CA, serializza sul main handler; l'apertura dell'adattatore non avvia il player. `MediaRuntimeCore` alimenta la policy, differisce i comandi e ricontrolla epoch, lease, target, consensi, route, chiamate e revisioni prima di `environment.execute`. Le transizioni di visibilità riconfigurano e revocano il lavoro accodato. Questo vale anche per target locali: il consenso background non è limitato a C6.
- `AndroidMediaEnvironment` distingue provider locale da controller esterni; i controller remoti/unknown restano inammissibili. Il singleton PLAYING viene selezionato includendo i contendenti, mentre target concorrenti producono ambiguità. NLS segnala accesso/sessioni senza creare il servizio o consenso; il relativo service nel manifest è protetto da BIND_NOTIFICATION_LISTENER_SERVICE. Nessuna seconda baseline STREAM_MUSIC nel provider locale: la policy fa da arbitro comune, mentre focus/attenuazione concorrente fanno rinunciare al duck.
- C6 usa consenso dedicato e campi `correlated*` distinti da `observed*`; route streaming e route storica sono esplicitamente inferenze. I callback ricontrollano identità/topologia, i timeout non sono ACK. Rimangono i limiti dichiarati su interventi non osservabili e routing di un renderer esterno. Callback fake non dimostrano effetto fisico.
- `PlaybackService.kt:67` collega il provider al player corrente, ricrea l'attachment sui cambi renderer e lo chiude nei percorsi di teardown. `PlaybackAirPodsProvider` usa identità account/sessione/route e osserva divergenze STREAM_MUSIC; una notifica Settings invariata non crea una ricevuta. Il comando locale setStreamVolume seguito da readback è distinto dalla sola uguaglianza del livello.
- Per Spotify, `core/.../LibrespotPlayer.kt:271` applica il comando al ramo locale senza Connect/resumeHere; `:1775` richiede URI e sequenza RX successiva al comando per la ricevuta pausa. Per file/video, `AirPodsExoOutput.kt:52` attribuisce pausa dopo applicazione al sink, con origin ordinata sulla playback queue; `LocalPlayerFactory.kt:98` collega il sink. Focus, comando manuale, account e route invalidano le ricevute. Il comportamento reale di decoder/JNI/AudioTrack rimane NV.
- Nel ramo gesti `AirPodsAndroidRuntime.kt:566`, una chiamata RINGING richiede l'azione call e termina il ramo con `return@collect`; il media dispatch successivo ha anche il controllo `noCallOrMix`. Non rilevata una ricaduta dalle azioni call alle azioni media nel percorso ispezionato. Effetti telefonici reali NV.
- Baseline ear, mono, UNKNOWN, overlap, primo CA live, ricevute persistenti, collisione ID dopo ricreazione e cleanup sono coperti da corpi test significativi letti. `CorrectiveMediaRuntimeTest` compone davvero refresh/coda/coordinatore, ma il provider resta fake; `PlaybackAirPodsMediaBridgeTest` usa un Player proxy e sink sintetico. I loro PASS non certificano il funzionamento completo con Spotify/native o Exo su Android.

## Evidenze e integrità

**Esecuzione personale:** verifica SHA256/size di tutti i 2.679 file a inizio review e nuovamente prima della consegna; digest manifest `a572b80b04a54a37beb333ac57638a03c6e485318457a97f0258ea6edd6e39b3`, zero mismatch. Verificato anche il solo hash/size dell'input screenshot privato esterno: nessuna copia, pubblicazione o ispezione ottica. Selettori input/artifact corrispondono ai digest di validation-selection.json. `audit.py` e `audit-final.json` documentano controlli riproducibili.

Le mappe parent before/preFinalTests/finalTests/after contengono ciascuna 1.356 input identici. **444** input sono inclusi come sorgenti nel pacchetto e verificati personalmente contro il record, zero differenze; **912** non sono presenti in questo contesto scoped. La loro identità test→build è attestata dalle mappe archiviate, non da una mia lettura del checkout live. Nessuna deduzione di repository autonomamente rebuildabile.

Riconfermata l'identità dei tre APK e mapping mediante manifest. Debug `500290a070bbacd39422c9342dfacb691d952105988189702b6da15a687b11ee`; minified `db3fb3d377caa2a80ef537d69eb686c61c9708cc3e8f296344be33e3c5e18484`; Wear `7c8067be158bc08dc73b8a6ba1c7f690964a0624ee4ecf77f8c28f23dfc18564`. Questi hash identificano gli artefatti, non provano equivalenza sorgenti/APK o esecuzione dei percorsi eliminabili da R8 con Product FALSE.

**Parent archiviato, verificato leggendo raw:** r3 targeted result.json/raw.log e 2 XML: 8 test, zero fail/error/skip. Extended result.json/raw.log e 81 XML: 686 test, zero fail/error/skip. Ho ricalcolato i conteggi dai nodi testsuite e verificato che gli otto mirati sono inclusi nell'extended: **non 694 test distinti**. Nel focus: coordinator 41, corrective core 11, runtime core 16, C6 provider 11, playback bridge 15; non sono conteggi aggiuntivi al 686. Raw log mostra il task test eseguito, con rerun/no-build-cache, non un mio nuovo run JUnit.

Build result/raw r3: exit 0 per un comando con assembleDebug, assembleRelease e wear assembleDebug. Artifact record: 24 checker exit 0; letti i risultati firma, metadata, ZIP16KB, ELF64 e class_defs selettivi. Le firme phone debug/release coincidono; Wear ha firma differente. Non è certificazione di linkage o interoperabilità Wear. App metadata 2.0.1(24), engine.properties 3.0.5. Vecchi 685 e r2 con un fallimento sono storia; Core18/Wear150 non risultano rieseguiti in r3. Nessuna mia build, Gradle, installazione, ADB, device, radio, lint o modifica di configurazioni LP/sistema.

**NV:** tre sessioni fisiche standalone, effetti telefonici/audio, reali diagnostici provider/JNI, Samsung/IME/TalkBack, continuità decoder e certificazione del candidato abilitato. Il telefono assegnato non costituisce evidenza. Il gate hardware resta separato e obbligatorio.

## Tabella obbligatoria

| Criterio | Voto0-10 | Statoverifica | Motivazione/evidenze | Problemi/gravità |
|---|---:|---|---|---|
| I1 | NV | Parziale, sorgente/catalogo | Letti requisiti e campione CA/ear/gesti; non riconciliate personalmente tutte le 35 famiglie, 67 preferenze e 41 esclusioni. | Completezza globale non certificata; C-01 incide sulle automazioni. |
| I2 | NV | Parziale, sorgente | Entry/host e requisiti letti; nessuna verifica visiva della Home completa, quick, setup signed-out o matrice geometrica. | Nessun difetto stilistico inventato; UX completa resta non verificata. |
| I3 | NV | Parziale, sorgente/test parent | Verificati consent/background e cancellazione media, NLS inerte e teardown provider; non audit completo C3/C4 di tutte le API, associazioni e ingressi. | Nessun PASS lifecycle globale derivato dal solo focus media. |
| I4 | NV | Parziale, sorgente pinned | Mapping eventi CA e generazioni media ispezionati; codec/capability/RX/pending completi non rivalidati personalmente. | Nessun voto protocollo globale. |
| I5 | 7 | Eseguito host + sorgente + parent archiviato | Probe core/coda riproduce perdita di ownership CA e cancellazione richiesta da UNKNOWN ear. Test significativi sugli altri casi esaminati. | C-01 medio: indipendenza dei motivi violata. |
| I6 | 8 | Verificato nel perimetro software/evidenze | Hash completi, selector, 444 input presenti, conteggi XML r3 e raw build/checker verificati; corpi test media letti. Limiti dei 912 input assenti, fake, R8/JNI/hardware espliciti. | Il PASS test non copre C-01; aggiungere regressione integrata. Nessun PASS runtime/hardware. |
| I7 | NV | Parziale, sorgente/provenienza | Campione media usa SDK ordinario e fonte pinned; Engine dichiarato 3.0.5. Non audit esaustivo asset/licenze, tutte le esclusioni o delta storico. | Confini globali non certificati da questa review. |
| I8 | 7 | Eseguito host + sorgente integrata | Tracciati runtime→core→coda→environment→bridge locale/C6→native/Exo e ramo calls; C-01 si manifesta nella composizione reale core/coda. | C-01 medio. Preservazione di tutto il lavoro estraneo e decoder reale restano NV. |
| S1 | NV | Fuori dal focus approfondito | Letti scope e raw test selezionati; non rieseguito audit di download/search/playlist/date e loro wiring. | Nessun voto dedotto dagli otto test verdi. |
| H1 | NV | Non eseguito, obbligatorio separato | Nessuna evidenza fisica nuova del candidato, nessuna delle tre sessioni autorizzata/eseguita da me. | Product FALSE; debug permit non abilita il prodotto. |

I criteri NV richiedono la copertura degli altri focus o ulteriori evidenze; questo report non li trasforma in approvazioni. Il difetto C-01 resta da correggere e verificare senza resettare il contatore CORRETTIVO2.
