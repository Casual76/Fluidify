# Review del piano completo AirPods — F0

Fase **PIANIFICAZIONE**, giro **INIZIALE**, versione **F0**. Reviewer A e B:
**GPT-6.1 Sol, reasoning high**, entrambi nuovi e indipendenti dagli autori.

Manifest congelato: `ea4ed8eee1448d9ceb125c87398f545d88806712ab49274ac0a9a08efa39e15b`,
270 file e1 input screenshot esterno. Entrambi hanno verificato integrità e fonti;
controlli propri, dati esterni e NV restano distinti nei rispettivi report.

| Criterio | A — Sol high | B — Sol high | Media | Minimo | Esito e problemi aperti |
|---|---:|---:|---:|---:|---|
| P1 — Catalogo | 7 | 8 | 7.5 (2/2) | 7 | Correggere A-R1 |
| P2 — UX | 9 | 9 | 9 (2/2) | 9 | Adeguato nel piano |
| P3 — Servizio | 8 | 7 | 7.5 (2/2) | 7 | Correggere B-R1/B-R2 |
| P4 — Stato/protocollo | 7 | 8 | 7.5 (2/2) | 7 | Correggere A-R2 |
| P5 — Automazioni | 8 | 7 | 7.5 (2/2) | 7 | Correggere B-R3 |
| P6 — Gate | 7 | 9 | 8 (2/2) | 7 | Correggere A-R2 |
| P7 — Confini | 8 | 9 | 8.5 (2/2) | 8 | Adeguato nel piano |
| P8 — Ownership | 8 | 8 | 8 (2/2) | 8 | Adeguato nel piano |
| H1 — Hardware | NV | NV | — (0/2) | — | Nessuna prova fisica nuova |

Medie e minimi solo sui due voti numerici comparabili del medesimo giro; NV escluso,
non zero. Nessun voto provvisorio o report mancante.

**Decisione root:** F0 richiede correzioni. Cinque rilievi medi confermati da esaminare:
A-R1 preferenza formato head tracking; A-R2 capability RX indeterminata; B-R1
registrazione presenza companion; B-R2 riconoscimento stop Task Manager; B-R3
tabella transizioni ear. Nessun difetto grave confermato. Le soglie individuali
non sono tutte raggiunte; la media P6=8 non nasconde il minimo7.

F1 sarà il primo giro correttivo della nuova pianificazione, senza modificare F0,
I3/G1 o i contatori storici. Nessuna implementazione o abilitazione hardware da questa
review. Tabelle originali e report integrali sono conservati sotto.

Review A: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3Adecb74ac-24b3-411c-b803-4007aa065483%3Adelegate-task%3A8b84612f-73e1-4b26-9b4b-53f5184149d0>

Review B: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3Adecb74ac-24b3-411c-b803-4007aa065483%3Adelegate-task%3A962c9b18-774c-4f06-b2f2-9ddfa539d63a>

## Report integrale A

**F0 richiede correzioni prima dell’approvazione del piano:** rilevo due lacune di gravità media in completezza funzionale e contratto delle capability. Non rilevo difetti gravi confermati. L’assenza di hardware non incide sui voti del piano.

Reviewer indipendente: **GPT-6.1 Sol, reasoning high**. Fase **PIANIFICAZIONE**, giro **INIZIALE**, versione **F0**. Nessun subagente.

Ho verificato personalmente:

- Manifest SHA-256 `ea4ed8eee1448d9ceb125c87398f545d88806712ab49274ac0a9a08efa39e15b`; **270/270 file** corrispondenti per hash e dimensione.
- Manifest interni: **133 catalogo + 36 architettura + 49 UX**, zero discrepanze; anche i 133 Git blob SHA-1 del catalogo corrispondono.
- Screenshot esterno: dimensione/hash corrispondenti, osservato privatamente.
- Piano, coverage, criteri, decisioni, contratto Android, catalogo JSON, report discovery e sorgenti pertinenti congelati. Nessun AGENTS applicabile trovato nei percorsi controllati.

Nessuna scrittura, build, test runner, installazione, dispositivo o modifica di impostazioni. Nessun vecchio report di review o comunicazione dell’altro reviewer aperto. Nessuna nuova fonte esterna acquisita: le fonti Android citate nei documenti restano **evidenze del dossier**, non verifiche online eseguite da me.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| P1 — Catalogo esaustivo | **7** | Verificato staticamente | Riconciliati 38 controlli e 38 funzioni: 35 incluse/adattate, 41 altre. Confrontati percorsi attivi per audio, accessibilità, chiamate, steli/testa, connessione e hearing. Una preferenza attiva resta senza decisione semantica obbligatoria. | R1, media |
| P2 — UX Home/player | **9** | Piano verificato; resa visiva NV | Piano e UX considerano marchio visibile distinto dal placeholder, FriendFaces, inset tablet, host condiviso, backdrop senza autocampionamento, quick/full settings e continuità player. Matrici viewport/font/back determinate. | Nessun rilievo confermato |
| P3 — Servizio/lifecycle | **8** | Contratto statico verificato; runtime NV | Servizio autonomo `connectedDevice`, opt-in separati, indipendenza da playback/account, CDM come trigger condizionato, stop/suppress e fallback manuale espliciti. Manifest attuale correttamente trattato come incompleto rispetto al futuro servizio. | Nessun rilievo confermato |
| P4 — Protocollo/stato/capacità | **7** | Verificato staticamente | Owner unico, generazioni, writer, pending osservativo, payload completi e distinzione RX/cache/default sono adeguati. Rimane indeterminato il requisito di capability ricevuta. | R2, media |
| P5 — Automazioni/manuale | **8** | Contratto statico verificato; effetti reali NV | CA config/eventi/effetti distinti; arbitro comune, motivi pausa sovrapposti, token sessione/route/account, cancellazione rampe e rinuncia al ripristino non attribuibile. NLS non equiparato a conferma delle azioni. | Nessun rilievo confermato |
| P6 — Gate/validazione | **7** | Piano verificato; esecuzione NV | Separazione software/host/fisico/UI corretta, tre sessioni standalone e nessuna promozione dello storico G1. Il criterio RX capability eredita l’ambiguità R2. | R2, media |
| P7 — Confini/dipendenze | **8** | Verificato staticamente | Esclusioni Xposed/ATT e takeover motivate; PPE distinto. NLS/CDM/Tile trattati come integrazioni ordinarie con consenso. Provenienza GPL, asset e nessun aggiornamento engine/release definiti. | Nessun rilievo confermato |
| P8 — Ownership/integrazione | **8** | Piano verificato | Perimetri dominio/runtime/UI e integratore dei file condivisi individuati; migrazione diagnostica esclusiva, preservazione concorrenti/Wear e serializzazione build/device previste. | Nessun rilievo confermato |
| H1 — Hardware | **NV** | Non eseguito | Nessuna nuova prova fisica autorizzata. Trasporto, RX effettivo, gesti, automazioni e affidabilità OEM restano NV; G1 storico invariato. | Non penalizza il piano |

I voti valutano la pianificazione, senza attestare implementazione o funzionamento.

**1. R1 — Preferenza attiva dei pacchetti alternativi di head tracking senza mapping determinato — media, P1.**

Il trigger è l’implementazione delle sole 35 voci obbligatorie, usando per head tracking un unico percorso scelto implicitamente.

Nel sorgente congelato `catalog/sources/android/app/src/main/java/me/kavishdevar/librepods/`:

- `presentation/screens/AppSettingsScreen.kt:344–350` espone realmente il toggle `use_alternate_head_tracking_packets`.
- `services/AirPodsService.kt:3145–3172` usa questa preferenza per scegliere **entrambi** i pacchetti start/stop.
- Il [catalogo JSON](/C:/VibeCoded%20Projects/Fluidify/build/review-airpods-full-F0/source/build/airpods-full-integration/catalog/catalog.json:11199) registra la chiave, ma `semantic_feature_ids` è vuoto a linea 11247. La voce `host.head_gestures` cita il default, senza stabilire il trattamento della scelta.
- La [coverage obbligatoria](/C:/VibeCoded%20Projects/Fluidify/build/review-airpods-full-F0/source/docs/airpods-full-integration-coverage.md:48) include toggle gesti, tracking e grafico, senza richiedere questa opzione o motivarne l’assorbimento/esclusione.

La conseguenza è concreta: due implementatori possono dichiarare completata la stessa voce conservando comportamenti diversi; un dispositivo che necessita dell’altro formato perde una possibilità attiva upstream.

**Correzione minima:** collegare la preferenza a `host.head_gestures` e decidere esplicitamente se esporla come opzione avanzata oppure assorbirla in una selezione motivata. Definire default, persistenza e accoppiamento start/stop per ciascun formato, mantenendo takeover escluso. Non serve implementarla oggi.

**2. R2 — Capability RX obbligatoria senza un contratto di ricezione identificato — media, P4/P6.**

Il trigger è un dispositivo con identità ricevuta e controlli funzionanti, ma senza un distinto annuncio capability decodificabile.

Il [report architetturale, linea 69](/C:/VibeCoded%20Projects/Fluidify/build/review-airpods-full-F0/source/build/airpods-full-integration/architecture/REPORT.md:69) propone l’intersezione con una «capability RX validata». Il [gate integrato, linee 292–296](/C:/VibeCoded%20Projects/Fluidify/build/review-airpods-full-F0/source/docs/airpods-full-integration-plan.md:292) richiede «RX modello/capacità».

Ho controllato il percorso upstream congelato: `AACPManager.kt:248` dichiara `onCapabilitiesReceived`, mentre `AirPodsService.kt:1175–1177` ne contiene l’override TODO. La ricerca nei componenti Bluetooth/servizio non individua un’invocazione del callback o un decoder capability; la tabella modello è invece statica. Il dossier stesso riconosce questo limite.

Non deduco che il firmware non possa trasmettere capability. Il problema del piano è **quale evidenza debba soddisfare quella condizione**: tabella derivata dal modello ricevuto, risposta della singola proprietà oppure messaggio dedicato ancora non specificato. Le interpretazioni producono abilitazioni e verdetti di gate diversi.

**Correzione minima:** distinguere capability dichiarata dal modello, eventuale capability annunciata e supporto osservato per proprietà. Specificare quali evidenze bastano per ciascuna funzione; se resta obbligatorio un annuncio dedicato, identificarne layout/fonte e gate di acquisizione. Non trasformare la tabella statica in RX né richiedere un messaggio indefinito.

Ho inoltre esaminato questi candidati senza considerarli rilievi:

- **PPE contro hearing/ATT:** `HearingProtectionScreen.kt:85–114` conferma due percorsi distinti. L’esclusione vendorIdHook riguarda loud sound reduction; PPE AACP resta incluso correttamente.
- **Stem senza takeover:** la bitmask `STEM_CONFIG` è distinta dal ciclo modalità; il catalogo conserva questa distinzione. Le azioni enum non esposte dalla UI non diventano nuovi requisiti.
- **Head tracking:** il ramo takeover upstream è reale, ma F0 lo esclude e richiede una prova dedicata. L’assenza di quella prova oggi è NV, non difetto del piano.
- **Range e RX:** verificati microfono 0/1/2, pressioni 0/1/2, swipe 1/2/3, chime `[valore,0x50]`, chiamate `[0,2]`/`[0,3]`, EQ 0–100 e separazione dall’ottimismo TX upstream. Nessun errore confermato.
- **NLS/CDM/Tile:** non configurano di per sé privilegi di sistema vietati; il piano conserva consenso, prerequisiti e fallback.
- **Disconnect non indossate:** l’adattamento best effort non è dichiarato funzionante; indisponibilità richiede evidenza concreta.
- **Gate UI:** lo sviluppo di fixture è distinto dall’abilitazione reale. Non rilevo una richiesta di promuovere hardware a PASS tramite simulazioni.

La soglia F0 di almeno 8 per ogni criterio non è raggiunta per **P1, P4 e P6**, per le due correzioni documentali sopra.


## Report integrale B

**F0 da correggere prima dell’approvazione del piano.** Ho individuato tre lacune circoscritte: contratto companion incompleto, riconoscimento dello stop di sistema e semantica delle transizioni orecchie. Non attribuisco alcun difetto alla mancanza di hardware.

Reviewer indipendente: **GPT-6.1 Sol, reasoning high**, harness Codex/T3. Fase **PIANIFICAZIONE**, giro **INIZIALE**, versione **F0**. Nessun subagente, scrittura, build, test runner o accesso dispositivo.

Ho verificato personalmente:

- manifest SHA256 `ea4ed8eee1448d9ceb125c87398f545d88806712ab49274ac0a9a08efa39e15b` e **270/270 file senza mismatch**;
- manifest interni: **133 catalogo + 36 architettura + 49 UX**, senza mismatch;
- hash e contenuto dello screenshot autorizzato, senza copiarlo o pubblicarlo;
- piano, coverage, criteri, decisioni, contratto Android, catalogo JSON, report discovery e sorgenti congelati pertinenti.

Nessun `AGENTS.md` trovato nei percorsi applicabili controllati. Ho letto le skill richieste e i riferimenti Fluid pertinenti. Le discovery sono trattate come analisi da verificare; nessun loro risultato hardware viene assunto come eseguito.

Riferimenti abbreviati, tutti sotto lo snapshot autorizzato:

- **P**: [piano integrato](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-F0/source/docs/airpods-full-integration-plan.md>)
- **A**: [discovery architetturale](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-F0/source/build/airpods-full-integration/architecture/REPORT.md>)
- **U**: [discovery UX](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-F0/source/build/airpods-full-integration/ux/REPORT.md>)
- **C**: [catalogo canonico](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-F0/source/build/airpods-full-integration/catalog/catalog.json>)

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| P1 — Completezza funzionale | 8 | Documentale e sorgenti mirati | C e coverage distinguono controlli attivi, dipendenze, funzioni latenti e adattamenti. Verificati anche toggle connessione/orecchie, PPE, Tile, disconnessione e percorsi hearing. Nessuna omissione attiva confermata nel campione falsificato. | Nessun rilievo confermato; non è una prova formale di completezza dell’intero upstream. |
| P2 — Home/player/quick/complete | 9 | Screenshot e sorgenti; resa runtime NV | P:251–281 e U:27–85 considerano entrambe le copie del marchio, collapsed, avatar/FriendFaces, inset tablet, overlay condiviso, backdrop, scroll finito e navigazione completa. | Nessun difetto progettuale confermato. Geometria e vetro effettivi ancora NV. |
| P3 — Servizio e lifecycle | 7 | Documentale, sorgenti e nuove fonti Android | Separazione da playback/account, FGS connectedDevice, opt-in, NLS e deduplica sono coerenti. Il nuovo percorso companion e lo stop di sistema hanno però contratti incompleti. | R1 e R2, **media**. |
| P4 — Protocollo/stato/capacità | 8 | Sorgenti statici e contratto | A:59–85 e P:220–234 separano RX/TX/cache, pending, generazioni, layout e capacità. Confermato nel sorgente upstream che l’invio aggiorna lo stesso contenitore usato da RX: la separazione proposta è necessaria. | Nessun difetto confermato; trasporto e supporto reale NV. |
| P5 — Automazioni/controllo manuale | 7 | Documentale e sorgenti | Buona separazione CA hardware/evento/effetto host; token, pause reasons e rinuncia al ripristino ambiguo sono espliciti. Manca la tabella operativa ear. | R3, **media**. |
| P6 — Gate e validazione | 9 | Documentale | P:283–313 mantiene standalone fisico prima dell’abilitazione UI reale, prove per famiglie e automazioni, tre sessioni e condizioni di completamento. Fixture e hardware restano distinti. | Nessun conflitto di gate confermato. |
| P7 — Confini/dipendenze | 9 | Catalogo e sorgenti mirati | Esclusioni motivate da dipendenze concrete; NLS/Tile non confusi con privilegi. PPE distinto da ATT hearing; nessuna promessa di takeover, modifica LP o aggiornamento engine. | Nessun rilievo confermato. |
| P8 — Ownership/integrazione | 8 | Documentale e confronto host congelato | P:84–98, A:59–75/122–130 e U:140–149 assegnano owner unico, integratore dei file condivisi, diagnostica esclusiva e preservazione del lavoro concorrente. | Nessun conflitto concreto confermato; handoff da rendere esecutivi nelle assegnazioni. |
| H1 — Prove hardware | NV | Non eseguite | Nessun telefono/prova fisica autorizzata in questa review. G1 storico resta NV. | NV non convertito in zero né in approvazione hardware. |

**R1 — Media: il percorso companion non specifica tutti i prerequisiti che lo rendono operativo.**

- **Trigger:** implementazione della riconnessione API31+ seguendo P:179–204.
- **Evidenza congelata:** il piano nomina associazione, `CompanionDeviceService` e `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`, ma non determina registrazione dell’osservazione, relativo permesso, verifica della feature e dichiarazione protetta del componente. Il contratto Android:52–62 non colma questi punti.
- **Nuovo dato esterno:** l’osservazione richiede `REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`, verifica di `FEATURE_COMPANION_DEVICE_SETUP` e registrazione tramite `startObservingDevicePresence`; il servizio richiede dichiarazione con `BIND_COMPANION_DEVICE_SERVICE` e intent filter. [CompanionDeviceManager](https://developer.android.com/reference/android/companion/CompanionDeviceManager#startObservingDevicePresence(java.lang.String)), [CompanionDeviceService](https://developer.android.com/reference/android/companion/CompanionDeviceService).
- **Conseguenza:** due implementazioni possono entrambe rispettare il testo, ma una limitarsi all’associazione senza ricevere i callback necessari. Il fallback manuale può nascondere un cablaggio mancante come indisponibilità della piattaforma.
- **Correzione minima/prova:** aggiungere il contratto manifest/API e la sequenza associazione → osservazione → callback → unico owner, con rami per versione e test di registrazione, revoca, disassociazione e assenza della feature.

**R2 — Media: lo stop dal Task Manager non ha un meccanismo definito per prevalere sui successivi ingressi companion.**

- **Trigger:** utente arresta Fluidify dal Task Manager Android mentre opt-in e associazione persistono; successivamente arriva un ingresso background.
- **Evidenza congelata:** P:195–218 controlla opt-in/suppress e richiede azione utente dopo arresto esplicito; A:93 distingue il Task Manager. Non è però definito come uno stop senza callback produca il suppress persistente.
- **Nuovo dato esterno:** lo stop dal Task Manager termina l’intera app senza callback; Android indica di controllare `ApplicationExitInfo` e `REASON_USER_REQUESTED` al successivo avvio. [Gestione dello stop utente degli FGS](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping).
- **Conseguenza:** un’implementazione che persiste suppress soltanto da Arresta/notifica può accettare il successivo callback companion usando il vecchio opt-in, contraddicendo la policy proposta.
- **Correzione minima/prova:** definire un controllo dello storico di uscita prima di qualunque acquisizione automatica, con consumo/versionamento dell’evento e comportamento conservativo quando indeterminato. Verificare separatamente Arresta interno, Task Manager e force-stop; nessuna riapertura fino alla riattivazione esplicita prevista.

Questo è un rischio dedotto dal contratto, **non un riavvio osservato sul dispositivo**.

**R3 — Media: “ear pause/resume” non determina quali transizioni causino gli effetti.**

- **Trigger:** RX valido `due → uno`, `uno → nessuno`, `nessuno → uno` oppure `uno → due`, con target media valido.
- **Evidenza congelata:** P:236–243 specifica ownership e sovrapposizioni; A:107 richiede transizioni per due/uno/nessuno, senza definirle. C, voce `host.ear_detection`, non aggiunge la tabella.
- **Controprova sorgente:** [AirPodsService.kt:1233](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-F0/source/build/airpods-full-integration/architecture/sources/AirPodsService.kt:1233>) contiene comportamenti differenti per rimozione di un auricolare e inserimento di uno da nessuno, incluso un ramo speciale a 1283–1286. Non esiste quindi una semantica unica ricavabile dalla parola “reinserimento”.
- **Conseguenza:** implementazioni divergenti su pausa con un solo auricolare e condizione di ripresa; “tre sequenze ear” nel gate non stabilisce quale risultato sia corretto.
- **Correzione minima/prova:** fissare una tabella stato precedente/nuovo → pausa/acquisizione motivo/rilascio motivo/ripresa ammessa, includendo primo RX, lato assente o unknown, uso mono e sovrapposizione CA. Collegare le sequenze del gate a esiti attesi precisi.

**Candidati esaminati che non configurano rilievo:**

- **Screenshot e due copie del marchio:** confermati nel frozen `HomeScreen.kt:648–737` e `:760–916`; il piano considera il marchio realmente disegnato, il placeholder e il centro residuo nel collapsed.
- **Player disconnesso contro Home nascosta:** P:271–279 stabilisce esplicitamente due comportamenti differenti; non è una contraddizione accidentale.
- **NLS ordinario contro privilegi:** il piano distingue accesso accordato dall’utente e permesso di binding del componente; non richiede `MEDIA_CONTENT_CONTROL` come runtime grant.
- **Ripristino volume/manual pause non attribuibili:** il fallback conservativo è già esplicito in P:242–243 e A:109–111; non assumo che i callback consentano un’attribuzione causale perfetta.
- **Gate standalone contro sviluppo UI senza telefono:** P:118–120/292–313 permette fixture e sviluppo inattivo, mantenendo chiusa l’abilitazione reale. L’assenza del telefono non annulla il gate.
- **Esclusioni funzionali:** hearing/trasparenza hanno dipendenze pinned motivate; PPE e Tile restano inclusi. Disconnessione ordinaria e head tracking senza takeover restano candidati da provare, senza successo anticipato.

I voti riguardano esclusivamente la qualità del piano F0. **P3 e P5 restano sotto soglia; H1 resta NV.** Le correzioni richieste precisano contratti già nel perimetro e non richiedono nuove feature o riapertura della ricerca di convivenza.



