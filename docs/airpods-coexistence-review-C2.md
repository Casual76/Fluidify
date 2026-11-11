# Review C2: ricerca di convivenza AirPods

Fase **RICERCA**, giro **CORRETTIVO1**, versione **C2**. Due reviewer indipendenti
**GPT-6.1 Sol, reasoning high**. Questa valutazione approva la qualità della ricerca
nel perimetro congelato; non certifica la convivenza né chiude la finestra delle otto ore.

Snapshot: `build/review-C2-coexistence`, manifest SHA256
`a395b72d620a8c7b14ea0cd738664fa9537a923602522bf82bdcb975ab24671b`.
Entrambi i reviewer hanno verificato 664/664 file e 7/7 input esterni.

| Criterio | A2 | B2 | Media | Minimo | Esito e problemi aperti |
|---|---:|---:|---:|---:|---|
| R1 — vincoli | 9 | 9 | 9 (2/2) | 9 | Soddisfatto nel perimetro documentale |
| R2 — copertura tecnica | 9 | 8 | 8,5 (2/2) | 8 | Analisi approvata; endpoint completo e background non dimostrati |
| R3 — esclusioni e controesempi | 9 | 8 | 8,5 (2/2) | 8 | Conclusioni circoscritte; nessuna impossibilità universale |
| R4 — provenienza e riproducibilità | 8 | 9 | 8,5 (2/2) | 8 | B1 e A-01 chiuse; esecuzioni indipendenti distinte sotto |
| R5 — integrazione e decisione | 9 | 9 | 9 (2/2) | 9 | B2 chiusa; ricerca ancora aperta |
| CP1.b — gate fisico | NV | NV | — (0/2) | — | Hardware non verificato; blocco prodotto invariato |

Media e minimo usano soltanto voti verificati e comparabili di questo giro/versione.
NV è escluso e non vale zero. Tutti i voti numerici individuali sono almeno 8; nessun
difetto materiale correttivo aperto. Il diverso livello di esecuzione R4 è esplicito:
A2 ha verificato staticamente la guardia e la logica Python con un sostituto dell'emulatore;
B2 ha rieseguito il runner ARM64 congelato in memoria con Unicorn, oltre a un negativo
specifico Register con budget 1. Il negativo salvato da root riguarda invece l'allocatore.
Nessuna di queste prove copre GAP/JV, Binder, radio, notifiche o durata.

**Decisione dell'orchestratore:** C2 approvata come checkpoint della ricerca. Nessun
ulteriore giro correttivo necessario per i rilievi B1, B2 e A-01. Restano separati
I3 software storico, G1/CP1.b NV e la decisione finale delle otto ore, con termine
**2026-11-08 03:52:42 UTC**. Nessuna UI AirPods, A2DP o lifecycle di prodotto sbloccata.
Nessuna impostazione LP/sistema, operazione sul telefono o modifica prodotto eseguita.

Review A2: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3Adf86da33-54c8-4950-8c85-12091aa1042d>

Review B2: <t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3Ad3acb4fb-f456-4760-a24d-35df2addcd19>

## Report integrale A2

**Reviewer A2 — GPT-6.1 Sol, reasoning high. Fase RICERCA, giro CORRETTIVO1, versione C2.**

C2 supera la mia verifica della qualità della ricerca. **Non ho trovato difetti materiali che richiedano un ulteriore giro correttivo.** B1 e B2 risultano risolti; A-01 è corretta nel codice, con i limiti di esecuzione indipendente precisati sotto. Questo giudizio non chiude la finestra di ricerca, non approva hardware/UI e non anticipa il ripiego esclusivo.

Ho applicato `agentic-workflow/SKILL.md` e `references/runtime.md`. Ho esaminato lo snapshot autorizzato, senza consultare altre review o voti C1. Il confronto con C1 è stato limitato ai manifest.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| R1 | 9 | Verificato documentalmente e sul delta | I vincoli restano espliciti; nessuna modifica al prodotto nei file confrontabili C1/C2. Le configurazioni globali non diventano soluzioni accettate. | Nessun difetto materiale rilevato. |
| R2 | 9 | Analisi verificata con letture mirate dei sorgenti | La [matrice integrata](</C:/VibeCoded Projects/Fluidify/build/review-C2-coexistence/source/docs/airpods-coexistence-evidence.md:34>) distingue registrazione, connessione, endpoint, RX e conferma. IPC, ATT/GATT, advertising e profili gestiti hanno limiti specifici per identità, batterie, mode e CA configurata. | Supporto remoto e background rimangono incognite dichiarate, non funzionalità certificate. |
| R3 | 9 | Verificate esclusioni e controipotesi principali | Le conclusioni restano circoscritte. Due Register confutano soltanto il limite intrinseco nella registrazione; macOS non viene trasferito a Samsung/Pro3. Sono definite condizioni concrete per riaprire GATT e IPC. | Nessuna esclusione universale ingiustificata individuata nella sintesi C2. |
| R4 | 8 | Integrità verificata; riesecuzione dinamica parziale | Manifest autorizzato coincidente; **664/664 file e 7/7 input esterni** conformi. Estratti controllati contro le righe originali pertinenti; nuovo fetch AOSP byte-identico. Guardia A-01 verificata staticamente e isolatamente. | Non ho ripetuto l’emulazione Unicorn completa; i risultati numerici restano esecuzioni dichiarate da autori/root. |
| R5 | 9 | Verificato sul materiale congelato | P1 resta aperto, I3 conserva il perimetro software storico, G1 resta NV. La [decisione successiva](</C:/VibeCoded Projects/Fluidify/build/review-C2-coexistence/source/docs/airpods-coexistence-evidence.md:118>) richiede una differenza osservabile e mantiene aperta la finestra. | Nessun avanzamento prematuro rilevato. |
| CP1.b | **NV** | Nessuna verifica fisica | Mancano prove contemporanee e durature delle due app, con audio/gesti/CA e lifecycle. Il telefono è indisponibile per mandato. | Gate hardware aperto; nessun voto numerico derivato dalle prove indirette. |

**B1 — risolta.** Il [contesto primario](</C:/VibeCoded Projects/Fluidify/build/review-C2-coexistence/source/build/airpods-8h/root/primary-context/README.md:9>) rende verificabili i riferimenti prima esterni alla raccolta. Ho controllato:

- identità delle due copie Java e nuovo fetch del commit AOSP fissato: SHA256 `fadecadbef65b6215ade7b3336980b8935687c7893e31ad67277551bcf78492e`;
- identità del btif con la copia già presente in `stack/aosp`;
- dimensione/hash dei metadata storici;
- testo sanitizzato e numerazione LF/universal dei due Socket Events e dei tredici RX mode.

Gli estratti sostengono eventi storici. Non ricostruiscono l’inventario attuale e non verificano nuovamente l’attribuzione PID della raccolta G1. C2 dichiara questi limiti.

**B2 — risolta.** Nel [report stack subordinato](</C:/VibeCoded Projects/Fluidify/build/review-C2-coexistence/source/build/airpods-8h/stack/REPORT.md:141>) la proposta è marcata esplicitamente come storica, superata e non eseguibile né ripresentabile. Non ho ripreso l’emulazione GAP/JV o svolto nuove analisi di bug/race di cleanup.

**A-01 — corretta, senza estenderne la portata.** In [emulate_rcb.py](</C:/VibeCoded Projects/Fluidify/build/review-C2-coexistence/source/build/airpods-8h/stack/emulate_rcb.py:23>) `call()` controlla il PC finale **prima** di leggere e accettare X0. Allocatore, finder e `register()` passano attraverso questa funzione. Un arresto per budget con PC diverso da `0x10000` genera `RuntimeError`.

Il controllo negativo congelato usa **l’allocatore con budget 1**, non una chiamata Register troncata. Questo copre la guardia comune; non va descritto come un negativo specifico del percorso Register. Ho eseguito separatamente la funzione estratta tramite AST con un sostituto minimale dell’emulatore: PC intermedio respinto, PC sentinella accettato. È una verifica della logica Python, **non esecuzione ARM64**.

Unicorn non è importabile nel Python corrente: non ho installato pacchetti né rieseguito il runner che scrive `emulation-result.json`. Le 32.768 maschere, i quattro scenari Register e il riuso incoming sono quindi risultati di autori/root verificati per coerenza con codice e artefatto. Le maschere riguardano allocatore/finder; non rappresentano 32.768 scenari Register. Callback, GAP/JV, Binder, radio, sottoscrizioni e durata non sono coperti.

Sul focus R2/R3, i tentativi di falsificazione non hanno prodotto una strada completa trascurata:

- **IPC stock:** il sorgente conferma `LocalBinder.getService()` senza remoting e broadcast RX indirizzati al package LP. Esistenza del servizio e comandi esportati non danno un feed completo.
- **GATT:** il percorso gestito differisce realmente dal socket AACP e può evitare una nuova registrazione raw. Mancano però endpoint e mapping remoto verificati; gli handle hearing/transparency non dimostrano mode o CA configurata.
- **LP che lasci ATT già disponibile:** questa possibilità positiva è già esplicitamente conservata nel [report notify](</C:/VibeCoded Projects/Fluidify/build/review-C2-coexistence/source/build/airpods-8h/notify-contract/REPORT.md:64>). Restano necessari discovery e RX originali, poi verifica dell’accessibilità Android.
- **Samsung:** il [report aggiuntivo](</C:/VibeCoded Projects/Fluidify/build/review-C2-coexistence/source/build/airpods-8h/samsung-artifact/REPORT.md:11>) mantiene correttamente NV il predicato OEM. Release note, 403 e metadata dei mirror non vengono usati come Java Samsung acquisito.

Come **dato esterno nuovo della mia review**, una ricerca pubblica mirata ha riportato anche la vecchia [issue upstream #20](https://github.com/librepods-org/librepods/issues/20), che menziona il GATT Find My della custodia. Non contiene un contratto completo per mode/CA: non modifica il giudizio C2 né costituisce una soluzione omessa dimostrata.

Nessun rilievo correttivo concreto da formalizzare con severità e fix. Restano aperte le prove discriminanti già identificate, senza penalizzare l’indisponibilità autorizzata del telefono. Non ho scritto file, eseguito build/installazioni o avviato subagenti.


## Report integrale B2

Reviewer **B2 — GPT-6.1 Sol, reasoning high**. Fase **RICERCA**, giro **CORRETTIVO1**, versione **C2**. Applicate skill agentic-workflow e `references/runtime.md`.

**Esito individuale: ricerca approvabile nel perimetro C2. B1, B2 e A-01 risultano corrette; nessun nuovo difetto materiale confermato.** Questo giudizio non conclude la finestra di ricerca e non certifica hardware, UI o convivenza.

Snapshot verificato: `build/review-C2-coexistence`, manifest SHA256 `a395b72d620a8c7b14ea0cd738664fa9537a923602522bf82bdcb975ab24671b`.

| Criterio | Voto | Stato della verifica | Motivazione ed evidenze | Problemi e gravità |
|---|---:|---|---|---|
| R1 | 9 | Verificato documentalmente | Matrice e report subordinati mantengono LP invariata/attiva e i divieti. Il prerequisito AVRCP globale non viene trasformato in soluzione accettata. B2 revoca esplicitamente la proposta GAP/JV. | Nessuna violazione concreta riscontrata. |
| R2 | 8 | Verifica statica mirata | Distinte identità/capacità, batterie nullable, mode, CA configurata e attività CA. IPC stock e GATT vengono valutati anche rispetto a ricezioni e background, senza equiparare invio e conferma. | Endpoint completo e comportamento concorrente restano non dimostrati; limite dichiarato. |
| R3 | 8 | Verificato su matrice e piste selezionate | Due Register confutano soltanto il limite strutturale nella registrazione. Le esclusioni conservano condizioni di riapertura; assenza di contratto pubblico e fallimento G1 non diventano impossibilità universale. | Nessuna strada completa trascurata confermata; ricerca pubblica non esaustiva. |
| R4 | 9 | Hash, riferimenti e prova Register riprodotti | Manifest corretto, 664 file e sette input esterni corrispondenti. Java ricontrollato contro nuovo fetch primario; estratti e numerazioni verificati. AOSP, OEM, sorgente, APK e hardware restano separati. | Nessun errore materiale confermato. Java/configurazione Samsung restano NV. |
| R5 | 9 | Verificato documentalmente | P1/I3/G1 conservano i rispettivi perimetri; codice prodotto invariato fra C1/C2. Prossima prova fisica subordinata a una differenza concreta e a nuova disponibilità autorizzata. | Finestra fino al **2026-11-08 03:52:42 UTC** ancora aperta; nessuna scelta esclusiva anticipata. |
| CP1.b | **NV** | Non verificabile in questo incarico | Nessun telefono disponibile e nessuna mia prova fisica. Registrazione sintetica ed evidenze storiche non dimostrano ricezioni concorrenti, durata, audio, gesti, CA o lifecycle. | Gate hardware aperto; nessun voto numerico sostitutivo. |

**B1 — chiusa nel perimetro richiesto.** In [primary-context/README.md](</C:/VibeCoded Projects/Fluidify/build/review-C2-coexistence/source/build/airpods-8h/root/primary-context/README.md:7>) le righe 9–13 mappano i riferimenti storici alle copie e agli estratti inclusi.

Ho verificato personalmente:

- `BluetoothSocket.java`: nuovo fetch HTTPS dal [commit AOSP fissato](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/c9bfb6b54a99e35872ce40f9c1ed7d3e86a7dd1a/framework/java/android/bluetooth/BluetoothSocket.java?format=TEXT), **43.421 byte**, SHA256 `fadecadbef65b6215ade7b3336980b8935687c7893e31ad67277551bcf78492e`, identico alla copia C2 e a `historical-BluetoothSocket.java`. Questo è un mio controllo esterno successivo al congelamento.
- `btif_sock_l2cap.cc`: identico alla copia già inclusa in `stack/aosp`.
- `versions.txt`: tutti i **277 byte** identici all’input storico.
- Due Socket Events e tredici RX mode: testo sanitizzato e riferimenti LF/universal corrispondenti agli originali verificati per hash. Nessuna esplorazione o pubblicazione di record estranei.

Le citazioni Java su costruzione, `connectSocket` e lettura dell’errore corrispondono alle righe dichiarate. La cronologia Socket Events non viene promossa a inventario attuale; l’attribuzione PID resta quella storica G1.

**B2 — chiusa.** [stack/REPORT.md](</C:/VibeCoded Projects/Fluidify/build/review-C2-coexistence/source/build/airpods-8h/stack/REPORT.md:141>), righe 141–145, qualifica direttamente la proposta come storica, superata e **non eseguibile né ripresentabile**. Non ho ripreso quella prova né analizzato o attivato race/bug di cleanup.

**A-01 — chiusa per la prova effettivamente coperta.** [emulate_rcb.py](</C:/VibeCoded Projects/Fluidify/build/review-C2-coexistence/source/build/airpods-8h/stack/emulate_rcb.py:23>) controlla il PC finale prima di accettare il risultato; righe 30–36 aggiungono il controllo negativo e la docstring delimita Register/allocatore/finder.

Ho rieseguito il runner congelato in memoria, adattando soltanto percorsi di input/dipendenza e sopprimendo la scrittura del risultato. Con Unicorn 2.1.4 passano **32.768 maschere**, capacità 15, quattro scenari outgoing e riuso server. Un mio ulteriore controllo sul solo Register con budget 1 viene respinto: `PC=0xa88b6c`, diverso dal ritorno `0x10000`. Nessuna emulazione GAP/JV, Binder o radio. La copertura non comprende connect, callback effettive, ricezioni o convivenza.

**Dato Samsung correttamente delimitato.** [samsung-artifact/REPORT.md](</C:/VibeCoded Projects/Fluidify/build/review-C2-coexistence/source/build/airpods-8h/samsung-artifact/REPORT.md:11>), righe 11–28 e 77–92, lascia esplicitamente NV Java OEM e predicato BIP. La release note congelata contiene gli identificatori citati alle righe 49–56; 403, dimensioni firmware e metadati mirror non vengono usati per dedurre comportamento OEM.

Nel tentativo di falsificare la sintesi ho ricontrollato soprattutto due percorsi positivi: broker IPC stock LP e GATT eventualmente già reso disponibile da LP. Il primo incontra concretamente `LocalBinder` e broadcast indirizzati al package LP; il secondo è già mantenuto aperto nel report notify, ma manca ancora un contratto remoto con mapping e RX completi per mode/CA. Non ho individuato una soluzione completa omessa, né elementi per chiudere universalmente queste possibilità.

I miei controlli sono quelli descritti sopra. Gli audit upstream, DEX, capture macOS e le altre raccolte restano esecuzioni dichiarate da root/autori, non mie riproduzioni. Ho consultato C1 soltanto tramite manifest per il delta: codice prodotto identico, nessun report o voto C1 letto. Integrità dei 664 file riconfermata al termine; nessuna scrittura o operazione sul dispositivo.


