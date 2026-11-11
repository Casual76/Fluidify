# Review del piano completo AirPods — F1

Fase **PIANIFICAZIONE**, giro **CORRETTIVO1**, versione **F1**. A e B sono due
nuovi reviewer indipendenti **GPT-6.1 Sol, reasoning high**, separati dagli autori.

Manifest `d0053b88dbe41b6b5f5927c4d6ec14bdaa9cff66f5dae52ecce6417639ddef64`,
273 file e1 screenshot esterno. Entrambi hanno verificato integrità/fonti; le
verifiche proprie ed esterne sono distinte nei report originali.

| Criterio | A — Sol high | B — Sol high | Media | Minimo | Esito e problemi aperti |
|---|---:|---:|---:|---:|---|
| P1 — Catalogo | 8 | 8.5 | 8.25 (2/2) | 8 | Adeguato come piano |
| P2 — UX | 9 | 8.5 | 8.75 (2/2) | 8.5 | Adeguato come piano |
| P3 — Servizio | 9 | 9 | 9 (2/2) | 9 | Adeguato come piano |
| P4 — Stato/protocollo | 8 | 8.5 | 8.25 (2/2) | 8 | Adeguato come piano |
| P5 — Automazioni | 8 | 8.5 | 8.25 (2/2) | 8 | Adeguato come piano |
| P6 — Gate | 8 | 9 | 8.5 (2/2) | 8 | R-D1 documentale bassa; indice F1 distinto verificato |
| P7 — Confini | 9 | 9 | 9 (2/2) | 9 | Adeguato come piano |
| P8 — Ownership | 8 | 8 | 8 (2/2) | 8 | Adeguato come piano |
| H1 — Hardware | NV | NV | — (0/2) | — | Nessuna nuova prova fisica |

Aggregati sui soli voti comparabili numerici di questo giro;2/2 per criterio.
NV escluso, non zero. Le cinque correzioni richieste risultano soddisfatte per
entrambi; nessun difetto bloccante confermato. Tutti i voti individuali almeno8.

**Decisione root:** piano F1 approvato nel perimetro pianificato. Avanzare a backend
completo e runtime, rispettando gate standalone e distinzione sviluppo/abilitazione
UI. Nessun voto autorizza a dichiarare completamento software o PASS hardware.

**R-D1, bassa:** il manifest interno originario degli artefatti catalogo è storico
rispetto a quattro file derivati aggiornati in F1. Root conserva quello originale e
ha prodotto `build/airpods-full-integration/catalog/artifact-manifest-F1.json`,
150 file verificati senza mismatch, SHA256
`54fa2138bb86464b24a561063bcaf5f5d7aa3a1f6dec72d47df94195d2c332d3`.
È una correzione di provenienza successiva al congelato, non un cambiamento dei
contratti né una nuova review; F1 congelato resta immutato. Usare l'indice distinto
per i derivati correnti; `artifact-manifest.json` rimane storico discovery.

Review A: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3Adecb74ac-24b3-411c-b803-4007aa065483%3Adelegate-task%3Ab41b7550-9787-4038-be1b-d006e7281ce5>

Review B: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3Adecb74ac-24b3-411c-b803-4007aa065483%3Adelegate-task%3A65951e00-f517-465f-90d1-567fe7a35eac>

## Report integrale A

**Esito: favorevole al piano F1 per questa review indipendente.** Le cinque correzioni richieste sono sufficientemente determinate nella fase di pianificazione. Non ho confermato difetti gravi residui; rilevo una discrepanza documentale minore nella provenienza degli artefatti.

Reviewer: **GPT-6.1 Sol, reasoning high**, harness Codex/T3. Fase **PIANIFICAZIONE**, giro **CORRETTIVO1**, versione **F1**. Non ho redatto gli artefatti esaminati.

Ho verificato direttamente:

- Manifest SHA256 `d0053b88dbe41b6b5f5927c4d6ec14bdaa9cff66f5dae52ecce6417639ddef64`: **273/273 file** corrispondenti per dimensione e hash.
- Le **133 fonti originali** del catalogo, le 36 copie architetturali e le 49 copie UX rispetto ai rispettivi hash.
- Piano, coverage, catalogo/generatore, contratti C1–C5, decisioni e contratto Android; lettura mirata dei rami originali pertinenti.
- Screenshot privato autorizzato: hash corrispondente e collocazione richiesta osservata, senza copiarlo o ripubblicarlo.

Nessuna scrittura, build, esecuzione di generatori/test runner, installazione o operazione su dispositivi. Il confronto F0 è limitato al delta; nessun report o voto F0 o dell’altro reviewer utilizzato.

I riferimenti abbreviati seguenti sono relativi a `build/review-airpods-full-F1/source/`.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| P1 — Completezza funzionale | **8** | Verifica documentale e sorgenti | Catalogo con 38 controlli, 38 funzioni supplementari e 67 preferenze; 35 voci incluse/adattate obbligatorie. Verificati formato tracking, range principali, comandi multi-byte, Tile e distinzione PPE/ATT. `coverage.md:19–54,125–148`; `plan.md:132–163`. | Nessuna omissione funzionale bloccante confermata. Completezza implementata ancora da dimostrare. |
| P2 — UX Home/player | **9** | Verifica del piano, screenshot e sorgenti congelati | Posizione fra wordmark/avatar, condizioni di visibilità, header doppio, FriendFaces/inset, pannello condiviso e impostazioni offline determinati. Backdrop e host Fluid trattati concretamente. `plan.md:257–287`; `ux/REPORT.md:77–85`. | Geometria finale, vetro e continuità visiva runtime NV. |
| P3 — Servizio autonomo | **9** | Verifica documentale e API primarie | Owner indipendente da playback/account, FGS connectedDevice, opt-in, avvii consentiti e arresti espliciti. C3/C4 completano presenza companion e soppressione persistente. `plan.md:170–224`; contratti C3/C4. | Affidabilità OEM e comportamento reale BG NV. |
| P4 — Protocollo/stato/capacità | **8** | Verifica documentale e rami originali | C2 distingue correttamente tabella statica, annuncio opzionale e osservazione per proprietà. Pending, generazioni, writer e layout/range hanno vincoli verificabili. `contracts.md:29–57`; `architecture/REPORT.md:59–85`; `plan.md:228–240`. | Decoder e guardie effettivi da verificare nell’implementazione; nessun supporto hardware dedotto dai vettori. |
| P5 — Automazioni/manuale | **8** | Verifica del contratto e confronto upstream | C5 determina baseline, mono, unknown, duplicati e override; motivi ear/CA separati e ripresa subordinata al token posseduto. L’autoplay LP senza pausa posseduta viene esplicitamente escluso. `contracts.md:134–176`; `plan.md:242–255`. | Attribuzione degli interventi manuali e targeting reale restano gate; prevista rinuncia conservativa quando ambigui. |
| P6 — Gate e validazione | **8** | Verifica della pianificazione | Backend, host, hardware e QA visiva distinti; prove per proprietà/famiglia, tre sessioni e scenari BG/ear/CA/cleanup espliciti. `plan.md:289–323`; C1–C5. | **R-D1, bassa:** manifest interno catalogo non aggiornato ai quattro artefatti F1 modificati. |
| P7 — Confini/dipendenze | **9** | Verifica documentale e sorgenti | Esclusioni riconducibili a dipendenze reali; NLS e Tile ordinari ammessi, nessun privilegio automatico, takeover o LP modificata. Licenze/asset e limiti del trasporto dichiarati. `integration-decisions.md:7–53`; `plan.md:156–190`. | Nessuna violazione sostanziale confermata. Accessibilità del trasporto sul target NV. |
| P8 — Ownership/integrazione | **8** | Verifica della pianificazione | Tre perimetri, integratore dei file condivisi, handoff e serializzazione, migrazione diagnostica esclusiva, preservazione Wear/concorrenza. `plan.md:90–118`; `architecture/REPORT.md:122–144`. | Firme e assegnazioni operative da concretizzare nell’handoff; confini sufficienti per pianificare. |
| H1 — Hardware | **NV** | Non verificato | Nessun dispositivo o nuova prova fisica autorizzata. Fixture, sorgenti e vettori TX non dimostrano ricezione o comportamento. | **G1 rimane NV**, senza reset di I3/G1/C1–C4. |

Il riesame delle cinque correzioni dà questi risultati:

- **A-R1 — Risolta nel piano.** C1, righe 7–27, impone l’opzione avanzata, default `true`, persistenza per coppia e formato attivo conservato. Il cambio durante tracking richiede stop nel vecchio formato prima dello start nuovo; errore stop invalida la sessione. Mapping presente anche in `catalog.json:3816,11264` e nel generatore `make_catalog.py:62`. Corrisponde al toggle upstream attivo e ai quattro encoder distinti; non importa il takeover.
- **A-R2 — Risolta nel piano.** C2 non rende necessario un decoder di capability indefinito: il callback upstream è effettivamente TODO (`AirPodsService.kt:1175–1177`). `canAttemptWrite`, identità corrente, applicabilità, permessi, range e pending sono espliciti. Una proprietà osservata non certifica le altre; timeout non significa incompatibilità.
- **B-R1 — Risolta nel piano.** C3 specifica feature, manifest, permessi, binding, registrazione effettiva, callback per versione e revoca. Ho confrontato questi requisiti con dati esterni primari Android: [CompanionDeviceManager](https://developer.android.com/reference/android/companion/CompanionDeviceManager) e [CompanionDeviceService](https://developer.android.com/reference/android/companion/CompanionDeviceService). Il comportamento OEM resta correttamente NV.
- **B-R2 — Risolta nel piano.** C4 pone il gate prima dell’avvio FGS e dell’acquisizione, distingue consumo dell’evento e soppressione, gestisce storico/marker indeterminati e richiede riattivazione esplicita. La premessa è corretta: Task Manager non consegna callback; al ritorno Android indica di controllare `ApplicationExitInfo`. [Fonte primaria esterna](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping).
- **B-R3 — Risolta nel piano.** C5 rende verificabili le transizioni stereo/mono/nessuno/unknown, il primo campione e gli override. Ear e CA mantengono motivi distinti; fine conversazione o reinserimento isolati non autorizzano una ripresa se resta un altro motivo.

**R-D1 — Discrepanza documentale, gravità bassa, non bloccante.**  
Trigger: verificare gli artefatti F1 usando `catalog/artifact-manifest.json`, anziché il manifest esterno del congelato.

Evidenza: le voci alle righe **13, 33, 38 e 58** conservano hash precedenti per `catalog.json`, `make_catalog.py`, `omissions-exclusions.json` e `REPORT.md`. I quattro file corrispondono invece al manifest F1 autorizzato.

Conseguenza: una verifica interna può segnalare falsi mismatch e rendere ambigua la revisione attestata. Correzione minima: qualificare esplicitamente quel manifest come storico oppure produrre un manifest interno F1 distinto, preservando l’originale. Prova richiesta: verifica hash senza mismatch per il manifest dichiarato corrente.

Le proposte discovery superate non costituiscono ulteriori rilievi: la precedenza normativa di C1–C5 e delle decisioni integrate è esplicita. Questa valutazione approva **l’implementabilità del piano**, senza attribuire PASS al software futuro, ai vettori wire o all’hardware.


## Report integrale B

**Esito: F1 approvabile come piano.** Non ho confermato difetti bloccanti né omissioni funzionali attive prive di trattamento motivato. I cinque rilievi indicati risultano risolti nella specifica; implementazione e comportamento fisico restano da verificare.

Reviewer indipendente: **GPT-6.1 Sol, reasoning high**. Fase **PIANIFICAZIONE**, giro **CORRETTIVO1**, versione **F1**. Nessun reset di I3/G1/C1–C4.

Ho verificato personalmente:

- SHA256 del manifest: `d0053b88dbe41b6b5f5927c4d6ec14bdaa9cff66f5dae52ecce6417639ddef64`.
- **273/273 file** conformi per dimensione e SHA256.
- Manifest interni: **133 catalogo, 36 architettura, 49 UX**, senza discrepanze; anche i Git blob dei 133 originali corrispondono.
- Screenshot esterno conforme al manifest, osservato privatamente.
- Catalogo, generatore/finalizzatore, contratti e fonti pertinenti congelate. Nessuna esecuzione di generatori, build o test runner; nessuna scrittura o attività su dispositivi.

Letti agentic-workflow e runtime, Fluid Engine e Material 3 pertinenti. Nessun AGENTS applicabile trovato nei percorsi controllati. Non ho consultato report, voti o riepiloghi F0 o di altri reviewer; il confronto storico è limitato al delta autorizzato.

Riferimenti abbreviati nella tabella: [Piano](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-F1/source/docs/airpods-full-integration-plan.md>), [Contratti C1–C5](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-F1/source/docs/airpods-full-integration-contracts.md>), [Copertura](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-F1/source/docs/airpods-full-integration-coverage.md>). I numeri seguenti indicano le righe dei file congelati.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| P1 — Completezza funzionale e catalogo | 8,5 | Verificato documentalmente | Catalogo riconciliato: 38 controlli, 38 funzioni supplementari, 67 preferenze, 11 modelli. Piano:132–163 e Copertura distinguono impostazioni attive, dipendenze, esclusioni e funzioni latenti. C1 rende obbligatoria l’opzione tracking alternativa. | Nessun difetto bloccante confermato. Supporto effettivo per modello/firmware da provare. |
| P2 — UX Home/player | 8,5 | Verificato documentalmente; resa runtime NV | Piano:257–287; UX REPORT:48–85. Posizione coerente con screenshot; visibilità setup+enabled+collegamento, errore AACP distinto, Settings offline, quick condiviso. Affrontati marchio visibile nel collasso, avatar/FriendFaces, target, inset, scroll finito, backdrop e navigazione. | Geometria, vetro, focus e continuità decoder richiedono QA prevista. |
| P3 — Servizio autonomo e lifecycle | 9 | Verificato documentalmente e contro fonti Android | Piano:170–224,251–255; C3:59–91 e C4:93–132. Servizio indipendente da playback/account, percorso CDM versionato, prerequisiti manifest, osservazione effettiva, revoca e gate precedente anche all’avvio FGS. | Affidabilità OEM e riconnessione reale NV; nessuna permanenza universale promessa. |
| P4 — Protocollo, stato e capacità | 8,5 | Verificato staticamente | C1/C2; Piano:228–240,291–304; architecture REPORT:59–85. Distinti RX, cache, default, pending, generazioni e tre forme di evidenza capability. Nessun annuncio indefinito imposto come prerequisito. | I vettori wire sono dati di riferimento, non test del client o conferme hardware. |
| P5 — Automazioni e controllo manuale | 8,5 | Verificato documentalmente | C5:134–174; Piano:242–249; architecture REPORT:103–120. Tabella ear determinata, baseline/mono/unknown, token confermati, motivi pausa sovrapposti, invalidazioni manuali e targeting condiviso locale/esterno. | Osservabilità degli interventi esterni e comportamento fisico NV; fallback conservativo esplicito. |
| P6 — Gate e validazione | 9 | Verificato documentalmente | Piano:289–323. Separati backend, host Android, standalone fisico, fixture UI e abilitazione reale. Scenari discriminanti per C1–C5, tre sessioni e verifica delle famiglie incluse. | Nessun PASS trasferito da file presenti, fixture o prove storiche. |
| P7 — Confini e dipendenze | 9 | Verificato staticamente | Piano:151–163; integration-decisions.md; fonti ATT/manifest pinned. Esclusioni collegate ai percorsi effettivi; PPE distinto da hearing/ATT. Notification listener e Tile ordinari ammessi senza privilegi inventati. | Nessuna promessa di convivenza, takeover, LP modificata, root o aggiornamento engine. |
| P8 — Ownership e integrazione | 8 | Verificato documentalmente | Piano:90–104; architecture REPORT:122–144; UX REPORT, ownership. Tre perimetri sostanziali e integratore unico per Application, manifest, routing e risorse; handoff e integrazione seriale dei file condivisi. | Le firme definitive restano lavoro d’implementazione; responsabilità e confini sono sufficientemente determinati. |
| H1 — Prove hardware | **NV** | Non verificato | Nessun dispositivo utilizzato o nuova evidenza fisica acquisita. Accesso AACP, tracking, readback, automazioni, background e materiale reale non dimostrati da questa review. | **G1 resta NV**; nessuna approvazione hardware o prodotto completo. |

Il riesame delle cinque correzioni dà questi risultati:

| Rilievo | Esito nella pianificazione F1 | Evidenza discriminante |
|---|---|---|
| **A-R1 — Formato tracking** | Risolto | C1:9–27 impone opzione avanzata, persistenza per coppia e default `true`. Catalogo e catena `make_catalog.py`→`finalize_report.py` mantengono il collegamento a `host.head_gestures`. Il cambio attivo richiede **stop del formato attivo prima dello start nuovo**; errore stop chiude la sessione. |
| **A-R2 — Capability RX indefinita** | Risolto | C2:31–57 riconosce il callback upstream TODO e separa `modelDeclared`, `deviceAnnounced` opzionale e `propertyObserved`. Definiti `canAttemptWrite`, presentazione candidata senza selezione inventata e gate per proprietà/famiglia. |
| **B-R1 — Osservazione CDM incompleta** | Risolto | C3:61–90 specifica feature, permessi, servizio protetto, registrazione e rilascio. Rami 26–30, 31–32, 33–35 e 36+ distinti; associazione, osservazione e collegamento reale non sono equiparati. |
| **B-R2 — Task Manager senza callback** | Risolto | C4:95–132 colloca il gate prima di FGS/socket e tratta ExitInfo, consumo atomico, suppress persistente e marker indeterminati. Consumo dell’evento non riattiva; apertura app/binding non equivalgono a Riattiva. Sono previsti ingressi concorrenti e vecchio/nuovo evento dopo riattivazione. |
| **B-R3 — Transizioni ear** | Risolto | C5:136–174 determina baseline, stereo→mono, nessuno→mono, scambio lato, duplicati e unknown. Manual PLAY invalida ownership; ear e CA mantengono motivi distinti e soltanto l’ultimo rilascio può considerare una ripresa posseduta. |

Ho cercato controesempi nelle impostazioni attive: formato alternativo, PPE rispetto ad ATT, Tile, disconnect quando non indossate, EQ, gesti e funzioni camera/HRM/case latenti. Le esclusioni e gli adattamenti risultano spiegati dalle dipendenze concrete o dall’assenza di un percorso Android attivo. Le formulazioni discovery superate non lasciano una norma concorrente: Piano:13–17 e Contratti:3–5 stabiliscono esplicitamente la precedenza F1.

Come **dati esterni primari**, ho riverificato le API di [osservazione CDM](https://developer.android.com/reference/android/companion/CompanionDeviceManager), le dichiarazioni e callback di [CompanionDeviceService](https://developer.android.com/reference/android/companion/CompanionDeviceService) e lo [stop utente dei foreground service](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping). Confermano i presupposti documentali di C3/C4; non dimostrano il comportamento sul telefono.

**Nessun nuovo rilievo confermato da correggere prima dell’implementazione.** Questa approvazione riguarda esclusivamente la qualità e implementabilità del piano F1; i gate software, UI reale e hardware rimangono quelli prescritti.



