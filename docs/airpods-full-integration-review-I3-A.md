# Review indipendente A — I3-stable

Reviewer non autore: GPT-6.1 Sol, reasoning high, tramite Codex/T3. Fase IMPLEMENTAZIONE, CORRECTIVE3: terzo giro correttivo dopo l'iniziale, senza reset. r2/r3/r4/r5 sono tentativi seriali di validazione, non nuovi giri.

Versione esaminata: `C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I3-stable` (S), manifest SHA256 `81c1ae56a4e59e6f94c9ca3c24dfbfbb0555d73ad78bf1f62a70f554c6fbd757`, 3377 file e 1 riferimento privato. Tutti i riferimenti sorgente sotto sono relativi a S/source. Pin LibrePods `89356c08cea3db0b6b54792d9329d09a87cc85fa`.

**Esito: nessuna approvazione complessiva da questa review. Un difetto P2 confermato mediante confronto dei predicati sorgente; copertura indipendente end-to-end di I1 incompleta. Product FALSE; H1/G1 NV.** Non è stato rilevato un difetto grave nei percorsi effettivamente approfonditi; questo non certifica quelli rimasti NV.

## Metodo, autorità e limiti

Letti brief A, fatti correnti I3, rubrica invariata, contratti C1-C6, piano e coverage, decisioni d'integrazione, contratto Android, catalogo corrente e fonti pinned, selection e record raw selezionati. Ispezionati i corpi dei test di protocollo/repository/Tile, codice di protocollo, controller, componenti Android, host e relativi editor UI. L'indice `required-evidence-read.json` registra membership/hash dei 103 riferimenti obbligatori: lettura dei bytes non equivale a validazione semantica di ogni log o runner.

Lette le istruzioni locali Fluid Engine, regole, Material3 e agentic-workflow/runtime. Nessun AGENTS trovato negli antenati controllati o nel source congelato. Nessuna memoria, rollout, altra review/voto o stato live consultato; nessuna subdelega. I report serial-validator e stem-disposition sono evidenze archiviate degli autori, non review indipendenti. Non è stato eseguito alcun loro runner.

Scritture solo nella directory I3-A autorizzata. Nessun Gradle/Kotlin/javac/JShell/compiler, dispositivo/ADB, install, impostazioni, radio, commit o pubblicazione. Il compiler baton resta a CV/PampAI. Non ho avviato nemmeno test JVM precompilati: tutti i controlli personali eseguiti sono parsing/hash/Python puro. Il probe tracking trascrive i predicati Kotlin: è un controesempio del modello e una deduzione diretta dal sorgente, non esecuzione della classe Android.

Legenda: **P** verifica personale eseguita; **A** esecuzione parent archiviata, ricontata dove indicato; **D** deduzione dal sorgente; **NV** non verificato. Un voto software non certifica hardware, APK abilitato o tutte le condizioni OEM.

## Tabella obbligatoria

| Criterion | Score0-10 | Verification status | Rationale/evidence | Problems/severity |
|---|---|---|---|---|
| I1 | NV | P/D parziale, A disponibile | Tutti i 35/67/41 record indicizzati individualmente; tracciati registry→editor→host→repository per i 19 controlli, informazioni/EQ/rename, Tile e tracking; sei stem approfonditi. Non completata falsificazione indipendente end-to-end di tutte le famiglie host, preferenze e dipendenze delle 41 disposizioni. | A-01 P2 tracking; completezza globale resta NV, non deducibile dall'indice. |
| I2 | NV | D parziale | Editor tracking, full settings e dispatch letti; nessuna QA personale del composito Home/player/onboarding, geometria/font/backdrop. | A-01 P2; qualità ottica e tutte le matrici layout NV. |
| I3 | NV | D parziale, A | Componenti Tile/FGS e gate di scrittura esaminati; osservazione Tile senza acquisizione; non completato audit di tutte le transizioni CDM/stop/persistenza. | Nessun nuovo difetto confermato nel sottoinsieme; SystemUI/OEM NV. |
| I4 | 8 | D approfondita + A, P integrità | Encoding/range/padding, EQ, identity, tracking, pending/RX, writer, generazioni e batterie esaminati in codec/repository; test discriminanti archiviati e XML ricontati. C2 mantiene separazione dichiarata/annunciata/osservata. | Nessuna violazione confermata nel software esaminato. Layout e readback reali, frequenza batterie e trasporto fisico NV. |
| I5 | NV | D parziale, A | Chiamata ringing intercettata prima del dispatch media; non ricostruita indipendentemente tutta la causalità EAR/CA/volume C5/C6. | Fixture e conteggi non bastano per un voto; effetti reali NV. |
| I6 | 8 | P parsing + A esecuzioni | Ricontati XML r5: negativo3/3fail, positivo3/0, targeted97/0, extended717/0; selected-record hash corretti; 24 check archiviati exit0; hash di tutti gli artefatti verificati con manifest. Corpi test codec/repository/Tile letti. | Nessun rerun personale, full lint/linkage/runtime NV; Core18/Wear150 storici. R8 gateFALSE non certifica APK abilitato. |
| I7 | 8 | P provenienza + D confini | 133 originali verificati per size/SHA256/Git blob; catalogo I3 152 file coerenti. JNI rimane sui due prefissi Bluetooth pinned, nessuna espansione telephony; componenti protetti e attribuzione GPL presenti. | Le esclusioni non diventano impossibilità firmware; non è audit legale/distributivo né prova dei privilegi OEM. |
| I8 | NV | D parziale, A | Host invia allo stesso controller/repository; Tile non apre sessione e cancella observer. Non completata verifica indipendente di preservation/decoder/bridge/Wear e tutte le navigazioni. | Nessuna equivalenza integrata dedotta dai check APK. |
| S1 | NV | A soltanto per la suite selezionata | Test data/search/library presenti nell'extended; non completato audit personale di cancellazione A-B-A, timestamp e fallback rete. | Fuori approfondimento A; nessun voto inventato. |
| H1 | NV | Non eseguito | Nessuna nuova sessione fisica autorizzata/eseguita da A. Tre sessioni complete standalone e readback mancanti. G1 storico resta NV. | Gate obbligatorio separato; Product FALSE. |

I voti 8 sono limitati all'evidenza software esplicitata, secondo la rubrica comune. NV non vale zero e non può contribuire a una media che certifichi il criterio. La review non chiude il gate complessivo: I1 e gli altri criteri NV richiedono copertura indipendente ulteriore, senza cambiare il contatore del giro.

## Rilievo A-01 — P2: Avvia tracking ignora un prerequisito effettivo

**Trigger:** sessione ammessa/ready, identità corrente con HEAD_GESTURES, preferenza headGestures ON, automationsEnabled OFF, UI visibile. È una combinazione valida: le preferenze sono separate e OFF resta revocabile.

**File/riga:** `app/src/main/java/dev/lelonio/square/ui/airpods/host/AirPodsHost.kt:238` abilita `tracking.start` con ready + headGestures + capability. `ui/airpods/AirPodsControls.kt:326` usa direttamente quel flag. `airpods/runtime/android/AirPodsAndroidRuntime.kt:355` richiede anche `p.host.automationsEnabled` e foreground-or-background per accettare lo start. `ui/airpods/host/AirPodsHost.kt:291` inoltra al controller; `:318` trasforma false nel rifiuto generico. Il reason selector `:171` non esplicita la mancanza di automazioni.

**Prova:** confronto personale del sorgente; `tracking-predicate-probe.json`/`probe.py` producono host_start_enabled=true, controller_accepts=false con gli stessi input. Non è prova di un click Android eseguito. Il predicato UI ignora completamente automationsEnabled, quindi il controesempio non dipende da timing/radio/OEM.

**Conseguenza:** azione resa disponibile che fallisce deterministicamente, senza la motivazione concreta richiesta. Non apre socket e non aggira il consenso: il controller blocca correttamente. Impatta usabilità/full-settings e completezza del percorso tracking, non sicurezza hardware.

**Correzione minima:** derivare la disponibilità dello start dagli stessi prerequisiti autorizzativi del controller, oppure condividere un selettore osservativo; motivare automazioni OFF e background mancante separatamente. Non indebolire il controller.

**Regressione proporzionata:** host test con sessione/capability/head ON e automazioni OFF: start disabilitato con motivo corretto; ON foreground: start disponibile; revoca OFF aggiorna subito; stop resta utilizzabile quando tracking attivo. Aggiungere il caso nel test di host reale con action sink. Nessun compiler eseguito per scrivere/eseguire questa regressione durante A.

## Protocollo e stato: tentativi di falsificazione

`AacpCodec.kt` + `protocol/AirPodsProperties.kt` non usano un generico booleano per tutto. CALL_MANAGEMENT è [0,value], CHIME [value,0x50], Adaptive inverte 100-value; padding residuo nonzero produce Unknown. MODE_MASK richiede almeno due bit e gli interlock escludono Off vietato/adaptive su modelli privi. EQ è opcode63 con length little-endian 5 e bande0..100; non sintetizza flat da RX troncata. Rename valida UTF8, NUL e lunghezza byte1..255. Questi limiti sono software conservativi, non range firmware misurati.

Il decoder accetta solo frame con header/layout attesi, gestisce i control prefix ripetuti solo per opcode9 e non cerca header arbitrari in payload sconosciuti. I frame variabili consumano il resto della SDU: non dichiaro framing universale di ogni messaggio firmware. INFORMATION richiede campi terminati UTF8 e manufacturer Apple Inc.; conserva nome/modello/manufacturer/seriali/versioni/hardware/updater e redige toString. L'upstream parser è più permissivo e salta zeri: l'accettazione dei layout reali resta NV, non un bug dimostrato in assenza di capture. Le fixture synthetic con tutti i campi non sono catture radio.

Tracking ha quattro TX distinti, standard/alternate start-stop; RX70byte e variant44/45, int16 little-endian signed verificati dai test con -32768/-1/32767. `setHeadTracking` serializza con tracking mutex, stop sul formato attivo prima dello start richiesto, invalida head e chiude su errore write. La preferenza alternativa è persistita per coppia e non è deviceAnnounced. Il tracking reale senza takeover resta NV. A-01 riguarda l'ammissione UI, non i byte.

`AirPodsRepository.kt` separa pending da Received, marca sequenza/packet/generazione e usa un writer Mutex. L'arrivo pertinente entro5s produce MATCH/OTHER; attività CA non conferma il toggle CA. Timeout è NOT_CONFIRMED, non incompatibilità; RX tardivo aggiorna observed senza riscrivere retroattivamente il timeout. Il piano esplicitamente lascia prevalere RX esterni: non tratto un RX prima del TX come ACK causale. Close invalida generazione, pending/tracking e chiude prima della cancellazione; connect10s usa una coroutine separata per interrompere I/O bloccante. Test letti comprendono writer concorrenti, close durante write/connect e vecchi RX. Non ho ri-eseguito le classi.

Batterie componenti2/4/8, percentuale nullable, status non riconosciuto→null, timestamp monotono e generazione. liveBattery vale meno di300000ms e solo connessione corrente; disconnect rende storico. Nessuna equivalenza tra batteria mancante e orecchio assente. ModelDeclared da tabella riconosciuta, deviceAnnounced null in assenza di decoder verificato, propertyObserved per singola proprietà. Nessun fallback Pro3 per unknown; C2 non richiede inventare un annuncio.

## Tile: ciclo completo osservato nel codice

`runtime/android/TileAvailabilityObservation.kt:14` combina state/runtime e rilegge tileUnavailableReason. `start` risolve l'owner esistente e avvia un solo collector; main dispatcher, epoch e listening/destroyed proteggono le notifiche; presentazioni uguali sono deduplicate; tile non attaccata non memorizza una presentazione fallita. stop cancella collector e riferimenti, destroy cancella scope. Nessuna factory/session acquisition nel percorso.

`AirPodsComponents.kt` collega start/stop/destroy e click; `ListeningModeCycle.kt` produce OFF→TRANSPARENCY→ADAPTIVE→ANC filtrato da canAttemptWrite. `AirPodsAndroidRuntime.kt:168-178` usa lo stesso selettore per disponibilità e click, con writable e mode corrente. Pending disabilita; RX/timeout riabilitano; runtime revocation e disconnect invalidano senza riaprire. L'Application installa il controller; get è una lettura dell'istanza.

I sette test AirPodsTileObservationTest montano l'observer di produzione con repository reale/fake transport e admission iniettata: pending→RX, timeout reale di scheduler5s, revoca senza RX, disconnect, ripetizione start/click/unrelated RX, stop/destroy e owner assente. XML r5 archiviato conferma7pass. Main è test dispatcher, non SystemUI. Lo shadow del framework TileService.onDestroy nel test component non prova il teardown OEM; questa limitazione non va nascosta con il pass dell'observer.

## Catalogo: cosa è stato realmente riconciliato

`catalogue-index.md` contiene individualmente tutti i 76 record controls/features (35 inclusi/adattati e41 altre disposizioni), più67 preferenze, con riferimenti upstream e stato NV end-to-end esplicito. È un indice verificato, non una matrice di143 PASS. La riconciliazione semantica totale richiesta da I1 **non è completata da A**: in particolare non ho chiuso ogni consumer/writer/ingresso delle preferenze non-stem e ogni effetto host. Nessun inventario/enum/six-key audit colma questo limite.

Per i19 controlli inclusi ho seguito la catena reale `AirPodsProperties.controls` → `AirPodsSettingsContent` (filterNot internalDependency/groupBy) → PropertyEditor (options/range/encoding) → Host.dispatch(Write) → controller.setProperty → repository.setProperty/codec → receive/propertyView. Tutti hanno un editor reale, non solo un ID. Sono: mic_mode, double_click_interval, click_hold_interval, listening_mode_configs, one_bud_anc_mode, listening_mode, chime_volume, volume_swipe_interval, call_management_config, volume_swipe_mode, adaptive_volume_config, conversation_detect_config, auto_anc_strength, allow_off_option, sleep_detection_config, ear_detection_config, automatic_connection_config, ppe_toggle_config, dynamic_end_of_charge. Il test all19Properties prova isolamento dei pending ma usa TX ri-decodificato: è software, non readback firmware.

Le16 famiglie semantiche sono nel registry ma la loro presenza non chiude il criterio. Tracciate in dettaglio rename→RenameEditor/host/codec/INFORMATION; battery→BatteryRows/liveBattery; information→full text di tutti11campi/requestInformation subscription; EQ→EqEditor/host/setEq/codec; head_gestures→local toggle/format/start/stop/plot/controller (A-01); long_press→GesturePicker/host/setLongPress/handleLongPress; QS→component/selector; diagnostics→collection/start-stop/export-delete/sanitized owner. Volume e le sei politiche ear/CA/disconnect sono collegate in host/controller ma la loro intera causalità/effetto non è stata falsificata personalmente: restano NV end-to-end. device.disconnect chiama audio operation distinta dal socket AACP; accepted/queued/settings non sono disconnessione osservata.

**Sei preferenze stem, controllate individualmente:** left_single/right_single default PLAY_PAUSE; left_double/right_double default inizializzato NEXT_TRACK; left_triple/right_triple PREVIOUS_TRACK. Service pinned:495-518 semina solo se assente; :1395-1426 legge config; :1510-1550 listener dinamico; :821-853 costruisce mask; :1094-1108 RX e :1188-1229 dispatch. I consumer sono attivi. Il writer dinamico AirPodsViewModel.kt:739-744 costruisce esclusivamente left/right_long_press_action; non raggiunge single/double/triple. PressAndHold/Nav sono long-press. Non ho trovato un editor configurabile nel corpus Kotlin/Java108file. La scansione letterale personale in stem-literal-scan.json non sostituisce una prova universale su codice generato/backup/dipendenze. Valori ripristinati/manuali restano consumabili; nessuna esclusione per privilegi inventata. Left-double getter/listener fallback PREVIOUS è distinto da default inizializzato NEXT, come documentato; non è stato importato come comportamento hardware Fluidify.

Fluidify setLongPress legge mask osservata e preserva bit0..2 con `(mask and 7)`; cambia soltanto bit3 e preferenze long per coppia. Non importa prefs private LP né offre una UI remapping nuova. La conservazione dei bit non prova default fisici o funzionamento. Le sei disposizioni latenti sono compatibili con il pin esaminato e non riducono le35famiglie.

Per le41 altre disposizioni: protocol_only non è impossibilità firmware; STEM è dependency interna; hearing/ATT/transparency è vincolato al percorso vendorIdHook pinned, PPE AACP resta incluso e con capability PPE. Accommodation/EQ phone-media e camera sono latenti, non editor da inventare; HRM/case non diventano funzioni dal solo enum. Widget/takeover/root/Xposed restano esclusioni esplicite; popups/global appartengono alla UX/distribuzione LP. Ho verificato esempi concreti HearingAidScreen/README e JNI, ma non ogni dipendenza esclusa: chiusura individuale completa delle41 resta NV nell'indice.

## Evidenza personale e parent archiviata

P: manifest iniziale3377+1, hash/size di ogni membro;133 originali pin con Git blob+SHA256+size; catalog manifest corrente152/152 senza mismatch; selected-record digest uguali a validation-selection. `personal-checks.json` contiene i riconti XML, `integrity-start.json` l'esito iniziale. Nessun source/APK equivalence dedotto da questi hash.

A ricontata personalmente: r5 negativo3test/3AssertionError (valore rosso gutter errato, non crash), positivo3pass; targeted97pass; extended717pass; nessun error/skip. Questi set si sovrappongono e non vanno sommati. Tre task build nel record exit0,24check artefatti exit0. Firma/ZIP/ELF/DEX selettivi hanno scope limitato. Non ho rieseguito signer/ELF/DEX checker né verificato tutto il linkage. L'APK minificato con product gateFALSE può eliminare percorsi; APK enabled richiede qualificazione separata.

Preservati nel dossier: iniziale94 con7fail; r2 baseline94/714pass; r3 timeout capture prima dell'asserzione RGB, r4 compilation failure ImageIO con zero test. Non li riclassifico come negative riuscite. Core18/Wear150 restano storici; non attribuisco nuovi pass senza comando. Full lint NV.

Non ho giudicato immagini/materiale ottico personalmente in questa review. La sentinella RGB dimostra copertura renderizzata page-floor nel fixture, non alpha interno, blur/tier, leggibilità o qualità vetro Samsung. Presenza semantica/portali non certifica la resa. I2 rimane NV per questa parte; il riferimento privato è stato solo verificato per hash/size, senza copia o pubblicazione.

## Integrità finale e consegna

Recheck completo al termine registrato in `integrity-end.json`: coincide con lo SHA richiesto, zero mismatch su3377+1. Nessuna mutazione del pacchetto o dei sorgenti; tutti gli output personali sono in I3-A. Nessuna abilitazione prodotto/hardware, nessuna release. Il report è completo rispetto ai risultati realmente ottenuti, ma non dichiara completata la copertura semantica globale I1 o i criteri NV.
