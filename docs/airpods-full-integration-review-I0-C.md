**Esito: I0 stabile richiede correzioni.** Ho riprodotto un difetto nell’arbitro del volume CA e un’omissione nella gestione della prima conversazione. H1 resta **NV**, senza penalità per l’indisponibilità del telefono; il gate prodotto deve rimanere FALSE.

Reviewer C: **GPT-6.1 Sol, reasoning high**. Fase **IMPLEMENTAZIONE**, giro **INIZIALE**, versione **I0 stabile**. Nessuna subdelega.

Ho verificato SHA256 del manifest `77cfdf2a…f3b659bb`, dimensioni e hash di tutti i **1130 file**, anche nuovamente al termine: nessuna difformità. Verificati anche i **133 sorgenti pinned** e l’input screenshot esterno, osservato senza copiarlo negli artefatti. Nessuna modifica ai sorgenti live o congelati, nessuna build, installazione o operazione hardware.

| Criterio | Voto | Stato verifica | Motivazione/evidenze | Problemi/gravità |
|---|---:|---|---|---|
| I1 — Completezza funzionale | 7 | Parziale, con difetti riprodotti | Le automazioni CA hanno percorsi effettivi, ma rampa/ripristino e primo evento attivo presentano controesempi. Non completata la riconciliazione semantica di tutte le 35 famiglie/67 preferenze/41 esclusioni. | R1 alta; R2 media |
| I2 — Home/player/quick/full settings | NV | Ispezione statica limitata | Esaminati riferimento privato, collocazione Home, visibilità, controlli Fluid e collegamento quick→full. Non verificato integralmente il percorso signed-out, navigazione e host completo nelle diverse configurazioni. | Nessun difetto confermato; copertura insufficiente per un voto |
| I3 — Lifecycle e ownership Android | NV | Ispezione parziale | Letti startup con conformance FALSE, start gate, manifest e teardown media. Non verificati integralmente tutti i rami CDM/FGS e l’unicità dell’owner attraverso ogni ingresso. | Gate aperto per verifica incompleta |
| I4 — Protocollo/stato/capacità | NV | Ispezione parziale | Controllati separazione RX/pending, generazioni, scritture serializzate, deadline e batterie nullable. Non completato l’audit dei layout e vettori pinned. | Nessuna certificazione complessiva del protocollo |
| I5 — Ear/CA/gesti/override | 6 | Approfondita sul focus C; checker propri | Distinzione locale/C6 corretta; controllo target/epoch e attesa PAUSED-before-receipt presenti. L’integrazione della coda interrompe però rampa e ripristino CA; il primo evento CA attivo viene ignorato. | R1 alta; R2 media |
| I6 — Validazione e artefatti | 7 | XML verificati; artefatti parent esaminati | Ricontati XML: app **555**, core **18**, Wear **150**, tutti senza failure/error/skip. I corpi letti coprono molti casi negativi, ma manca la regressione integrata che rivela R1. Build, firma, DEX e allineamento restano controlli del parent. | Lacuna concreta nei test di integrazione del volume |
| I7 — Confini/permessi/provenienza | 8 | Statica, nel perimetro esaminato | Pin e 133 hash coerenti; attribuzione GPL presente; consenso C6 dedicato e limiti dichiarati. Manifest con permessi ordinari espliciti. Engine **3.0.5**, versione **2.0.1(24)** confermati. | Nessuna violazione confermata nel perimetro letto |
| I8 — Integrazione/preservazione | 7 | Approfondita sul percorso media | Esaminati startup, bridge del player, factory Exo, sink, ricevute native e cancellazioni. R1 emerge proprio dalla composizione coordinatore→coda→provider. Preservazione dell’intero lavoro estraneo non attestabile dal congelato selettivo. | R1 alta; copertura restante parziale |
| H1 — Gate fisico standalone | NV | Non eseguito | Nessun telefono, radio o chiamata usati. Fixture, richieste accettate e test JVM non provano comportamento fisico. G1 storico resta separato e NV. | Gate obbligatorio aperto |

Tutti i riferimenti seguenti sono nel congelato `build/review-airpods-full-implementation-I0-stable/source/`.

**R1 — Alta: una ricevuta precedente cancella il successivo passo del volume, compreso il ripristino.**

- **Trigger:** CA attiva, primo SET_VOLUME applicato e corroborato; successivo passo della rampa oppure ripristino alla fine della conversazione.
- **Posizioni:** [AirPodsAutomationCoordinator.kt:132](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I0-stable/source/app/src/main/java/dev/lelonio/square/airpods/runtime/automation/AirPodsAutomationCoordinator.kt:132>), [MediaRuntimeCore.kt:175](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I0-stable/source/app/src/main/java/dev/lelonio/square/airpods/runtime/media/MediaRuntimeCore.kt:175>), [PlaybackAirPodsMediaBridge.kt:240](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I0-stable/source/app/src/main/java/dev/lelonio/square/playback/PlaybackAirPodsMediaBridge.kt:240>).
- **Prova propria:** checker JShell sulle classi congelate, con ambiente e coda deterministici. Volume iniziale 50, obiettivo 20: primo passo a 35; il successivo viene cancellato e, a fine CA, il volume resta 35. Con primo passo direttamente a 20, resta 20 anche dopo fine CA. Riprodotto anche passando l’evidenza attraverso i campi C6 esterni.
- **Causa:** `submit()` rilegge lo stato prima dell’esecuzione. Il provider conserva correttamente la ricevuta precedente; quando esiste già un nuovo `awaiting`, il coordinatore interpreta qualsiasi evidenza non nulla diversa dalla nuova come interferenza e abbandona la lease.
- **Conseguenza:** attenuazione incompleta e mancato ripristino durante il normale termine della conversazione.
- **Fix minimo:** distinguere la ricevuta già consumata da una nuova evidenza incompatibile; mantenere la revoca per divergenza effettiva o override. Aggiungere regressioni su `MediaRuntimeCore` con ricevute persistenti, almeno due passi, ripristino, variante locale e C6.

Checker: [volume-receipt.jsh](</C:/VibeCoded Projects/Fluidify/build/airpods-full-integration/reviewer-scratch/focus-c/volume-receipt.jsh>), [volume-full-step.jsh](</C:/VibeCoded Projects/Fluidify/build/airpods-full-integration/reviewer-scratch/focus-c/volume-full-step.jsh>), [external-volume-receipt.jsh](</C:/VibeCoded Projects/Fluidify/build/airpods-full-integration/reviewer-scratch/focus-c/external-volume-receipt.jsh>).

**R2 — Media: il primo evento CA attivo viene consumato come baseline senza applicare la policy.**

- **Trigger:** dopo avvio/configurazione, il primo evento voce valido è attivo, senza un precedente evento inattivo.
- **Posizioni:** [AirPodsAutomationCoordinator.kt:187](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I0-stable/source/app/src/main/java/dev/lelonio/square/airpods/runtime/automation/AirPodsAutomationCoordinator.kt:187>), [AndroidAirPodsMediaRuntime.kt:63](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I0-stable/source/app/src/main/java/dev/lelonio/square/airpods/runtime/media/AndroidAirPodsMediaRuntime.kt:63>).
- **Prova propria:** primo `conversation(true)` e relativo duplicato producono zero effetti; soltanto una successiva sequenza false→true avvia il duck. Il runtime inoltra gli stati attivi 1/2 senza inizializzare prima un inattivo. Anche il pinned upstream gestisce direttamente 1/2 come avvio conversazione.
- **Conseguenza:** una prima conversazione può non attivare pausa o attenuazione host. Non ho provato che l’hardware invii necessariamente un evento inattivo preliminare.
- **Fix minimo:** distinguere baseline/cache da evento live; gestire il primo attivo live oppure dimostrare e implementare una baseline preliminare affidabile. Testare primo attivo, duplicati, reconfigure e target recovery. I test attuali inizializzano esplicitamente CA=false, nascondendo questo caso.

Checker: [first-conversation.jsh](</C:/VibeCoded Projects/Fluidify/build/airpods-full-integration/reviewer-scratch/focus-c/first-conversation.jsh>).

Nel percorso locale ho riscontrato ricevute collegate all’applicazione del comando: ramo native con evento successivo pertinente e, per Exo, `ForwardingAudioSink.pause()` seguito dalla verifica dell’AudioTrack. Questo è più forte di un semplice stato PAUSED coincidente. I test native sostituiscono però i comandi reali con callback, mentre quelli della factory Exo non dimostrano un ciclo completo su audio decodificato. La riproduzione fisica resta NV.

Le cancellazioni per account, target, route, focus, consenso e intenti manuali, compresi i seek delegati, sono presenti nei percorsi letti. Il cleanup esaminato non emette PLAY né rollback. C6 usa campi di correlazione distinti e il consenso descrive i limiti osservativi; queste proprietà positive non risolvono R1.

**La review software non supera il gate:** occorrono correzione e regressioni per R1/R2, oltre alla copertura restante indicata NV. Il successo della build minificata con conformance FALSE non certifica i percorsi dopo una futura abilitazione.

Nota d’indipendenza: una ricerca preliminare nel registro memoria ha restituito accidentalmente estratti storici; non ho aperto i relativi report/thread né usato quei giudizi nelle conclusioni.
