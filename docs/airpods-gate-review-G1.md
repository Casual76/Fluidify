# Review del gate AirPods — evidenze G1, software I3

Fase GATE FISICO, prima valutazione delle nuove evidenze indossate del 2026-11-07. Codice e APK I3 invariati; nessun nuovo giro correttivo software o reset dei due giri consumati. Manifest G1: `4d737d00d09a9daa8ff59550306f95bcaab53dca9a114d605618dfa86046b172`, 16 artefatti.

## Riepilogo conclusivo

Reviewer A e B: GPT-6.1 Sol, reasoning high, indipendenti. Entrambi ricevuti sulla medesima raccolta G1. Aggregati sui soli voti numerici comparabili, esclusi NV; i precedenti voti software non vengono importati in G1.

| Criterio | A | B | Media (validi/attesi) | Minimo | Esito e problemi aperti |
|---|---:|---:|---|---:|---|
| CP1.b — Gate fisico | NV | NV | — (0/2) | — | Connect concorrente negativo; gate incompleto, UI/A2DP/lifecycle di prodotto sospesi |
| CP5 — Evidenze e tracciabilità | 8 | 8 | 8 (2/2) | 8 | Adeguate per localizzare il blocco e decidere di non avanzare; limiti espliciti |

**Decisione operativa: gate NV, nessun avanzamento prodotto.** Il connect Fluidify è fallito nella registrazione locale; è un esito negativo eseguito, non una mera assenza di osservazione. Gli scenari successivi obbligatori rimangono non verificati. Non è dimostrato il degrado funzionale riproducibile/reversibile richiesto dal FAIL complessivo definito nel piano. I voti CP5 non compensano CP1.b aperto.

Entrambi i reviewer confermano 16/16 artefatti G1 e80/80 file I3, identificano l’APK realmente installato e distinguono le ricezioni LP dalle scritture ottimistiche. Le nove transazioni LP sono entro5s, massimo3.385s; il singolo connect Fluidify fallisce prima di AACP. L’evento server UID1002/OBEX è una perturbazione tecnica osservata, senza misura di un danno funzionale; la continuità LP/audio non equivale ad assenza di qualsiasi effetto su altri profili.

## Precisazioni documentali e limiti accettati

- Il campo JSON `initialMode=Adaptive` è dichiarativo e non viene usato come prova della modalità al momento dello screenshot baseline G1, che mostra **ANC**. Il successivo stato Adaptive è invece riscontrato dalle ricezioni04. Si conserva la raccolta G1 immutata, senza correggere retroattivamente dati o hash.
- L’associazione temporale delle nove richieste/ricezioni usa stesso valore, ricezione successiva entro5s e consumo univoco. Non contiene un identificativo di transazione hardware e non prova causalità assoluta o convergenza tra due app. Nel primo gruppo alcune richieste si sovrappongono prima delle rispettive ricezioni.
- Indossamento e continuità audio percepita restano riscontri attribuiti all’utente. Dump e ricevute successive sostengono continuità osservata, non una misura continua di microinterruzioni.
- Oltre2min senza riapertura riguarda il **POC fallito**, non il lifecycle di una sessione connessa di prodotto. Arresto del logcat e rilascio del pannello sono azioni documentate da root, non verifiche hardware ripetute dai reviewer.
- Restano non attestati: tre sessioni concorrenti, comandi Fluidify/modalità e gesti bidirezionali, CA hardware bidirezionale, conversazioni/volume/pausa, lifecycle di prodotto.
- La nota statica aggiunta alla diagnosi sul possibile handle0 è un’ipotesi root esterna alla raccolta G1 e non viene presentata come conclusione verificata da questo giro. Non giustifica patch o nuovi tentativi fisici.

## Passaggio successivo

La ricognizione client ha individuato il limite della registrazione, non un bypass verificato. Il ramo di prodotto resta sospeso secondo il piano. Occorre una decisione esplicita sul requisito di convivenza: mantenerlo obbligatorio oppure valutare una modalità autonoma a uso esclusivo con gestione manuale dell’altro client e criteri di collaudo aggiornati. Nessuna chiusura automatica LibrePods, bridge/fork o modifica dello stack viene introdotta. Un eventuale cambio di requisito non equivale a un PASS hardware o all’autorizzazione a saltare i nuovi collaudi.

## Reviewer A

<t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3A543c6d74-9361-4819-a1d7-63c32dd9dc0a>

**Gate operativo: NV. UI AirPods, integrazione A2DP e lifecycle di prodotto non sbloccabili.** Il singolo connect Fluidify eseguito ha avuto esito negativo dimostrato; gli scenari successivi restano non verificati. Non risulta dimostrato il degrado reversibile richiesto per classificare il gate come FAIL.

Reviewer indipendente, focus A — **GPT-6.1 Sol, reasoning high**. Fase **GATE FISICO**, valutazione iniziale evidenze **G1**, software **I3**. Nessun nuovo giro correttivo software.

| Criterio | Voto | Stato verifica | Motivazione/evidenze | Problemi/gravità |
|---|---|---|---|---|
| CP1.b | **NV** | Gate obbligatorio incompleto; connect eseguito con esito negativo | Baseline parziale LP/audio riscontrata; nove richieste modo con ricezioni pertinenti entro 5 s. Fluidify fallisce prima di handshake/subscription/comandi. Mancano tre sessioni, convergenza tra entrambe le app e gesti, CA bidirezionale, conversazioni e lifecycle prodotto. | **Bloccante per avanzamento:** nessuna prova sufficiente della convivenza richiesta. Il negativo del connect resta esplicito; NV non equivale a zero né cancella quel risultato. |
| CP5 | **8/10** | Verificata, sufficiente per mantenere il blocco e circoscrivere la diagnosi | Integrità completa dei manifest; estrazione riprodotta in memoria; attribuzione Fluidify/LP distinta; riscontri utente identificati come tali. Evidenze adeguate alla decisione attuale, insufficienti a certificare gli scenari non svolti. | **Limiti documentali moderati:** diversi campi JSON sono dichiarativi; matching temporale senza identificativo transazione; continuità audio sostenuta da osservazioni puntuali e testimonianza, non misurata senza interruzioni. |

**Controlli eseguiti da me**

Ho verificato gli SHA256 indicati di manifest G1, log continuo, APK installato e source-manifest I3: coincidono. Corrispondono anche hash e dimensioni dei **16/16 artefatti G1**, e gli hash degli **80/80 file dello snapshot I3**. Il report root è stato soltanto sottoposto a hash. Queste verifiche provano l’integrità degli artefatti identificati; non costituiscono una ricostruzione indipendente dell’APK dai sorgenti.

Ho letto le due skill richieste; non ho trovato AGENTS applicabili nei percorsi esaminati. Nessuna build, scrittura, installazione o interazione con dispositivi; nessun impiego del checkout live come equivalente all’APK.

**Cronologia e associazione delle prove**

- **19:48:44:** il dumpsys iniziale registra il connect LP dal PID26763 e la connessione L2CAP stabilita alle **19:48:44.581**, prima dell’inizio cattura. La cattura copre **19:50:07.862–20:02:00.774**.
- **19:53:25:** riscontro AudioTrack Fluidify UID10595/PID31173; il dump audio registra il player `started`, mentre media e Bluetooth attestano playback locale e AirPods A2DP attive.
- **19:54:** confermate **nove richieste LP**, tre per ANC/Transparency/Adaptive, associate a nove ricezioni `Control command received: 0d` distinte. Latenze **0,871–3,385 s**. È ricezione separata dall’invio, non semplice aggiornamento ottimistico. Una richiesta ulteriore Adaptive alle **19:54:52.325** riceve `04` alle **19:54:53.753**, fuori dalla finestra delle nove.
- **19:57:11.419:** unico connect Fluidify, UID10595/PID31173, socket **83**, BR/EDR tipo3, porta4097. Seguono `no RCB available`, PSM1001/vPSM1004, fallimento GAP e inizializzazione socket83. Chiusura nativa immediata e `BluetoothSocket.close()` applicativo alle **19:57:11.420**. Evidenza: [log, richiesta e fallimento](/C:/VibeCoded%20Projects/Fluidify/build/airpods-worn-gate/continuous.log:114245).
- Nello stesso istante viene chiuso **id77, UID1002**, indirizzo nullo; segue creazione server UID1002 e allocazione **id84**, con registrazione PSM1001. È un evento distinto dal socket Fluidify e non attribuibile a LP. Dimostra attività collaterale dello stack; **non ne dimostra un danno funzionale**.
- **19:59:** LP invia ANC alle19:59:18.222 e riceve alle19:59:19.108; invia Adaptive alle19:59:20.944 e riceve alle19:59:21.866: rispettivamente **0,886 e 0,922 s**. Nessun connect/close LP rilevato dai selettori durante la cattura.
- **19:58:57.677:** distacco della finestra diagnostica. Restano **183,097 s** di cattura senza nuovo connect Fluidify. Media risulta PLAYING nel dump successivo al tentativo e PAUSED in quello finale.

**Limiti che incidono sul giudizio**

La riproduzione dell’[estrattore](/C:/VibeCoded%20Projects/Fluidify/build/airpods-worn-gate/summarize-evidence.py), eseguita esclusivamente in memoria prima della scrittura finale, restituisce lo stesso JSON. Il matching usa valore uguale, ricezione successiva entro5s e consumo univoco: è adeguato alle nove richieste osservate, ma non prova causalità assoluta o convergenza simultanea. Nel primo gruppo alcune richieste precedono la ricezione della precedente.

Dichiarazioni utente, descrizioni di baseline/cleanup, scenari non eseguiti e modi iniziale/finale sono inseriti manualmente nello script. In particolare, **`initialMode=Adaptive` non è corroborato dallo screenshot G1 di baseline, che mostra ANC**; il successivo ripristino ad Adaptive è invece riscontrato nel log.

Le conferme “indossate e collegate”, “audio e cambi regolari” e “nessuna interruzione percepita” restano **testimonianze attribuite all’utente**. Le ricezioni LP successive e i dump sostengono la continuità funzionale osservata, senza certificare assenza di microinterruzioni, gesti o CA. Gli eventi parser CA presenti non attestano conversazioni complete o toggle hardware bidirezionali. Terminazione del logcat e rilascio del pannello non sono verificati autonomamente dai dati esaminati.

La finestra oltre2min soddisfa l’osservazione della **mancata riapertura del POC fallito**; non verifica il rilascio di una sessione connessa né il lifecycle di prodotto.

Il problema concreto bloccante è quindi: **connect Fluidify con LP attiva → registrazione locale fallita → nessun canale Fluidify disponibile per provare la convivenza**. Non dimostra impossibilità generale del dual-channel, né regressione LP/audio riproducibile e risolta ritirando Fluidify.

Il prossimo passo utile nel perimetro autorizzato è una diagnosi statica mirata sui materiali congelati della registrazione e del server UID1002. Le evidenze attuali giustificano mantenere sospeso il ramo AirPods; non giustificano patch speculative, nuovi tentativi fisici o avanzamento alla UI.

## Reviewer B

<t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3Aa7eb1d9b-d24b-45e3-a8da-5de73e9bd08c>

Reviewer indipendente B — **GPT-6.1 Sol, reasoning high**. Fase **GATE FISICO**, prima valutazione evidenze **G1**, software congelato **I3**. Nessun giro correttivo software aggiunto o azzerato.

**Esito operativo: NV. UI e integrazioni A2DP/lifecycle di prodotto non sbloccabili.** Il connect Fluidify eseguito ha avuto esito negativo dimostrato; gli scenari successivi restano non verificati. Non risulta dimostrato il FAIL definito dal piano, che richiede un degrado riproducibile e reversibile ritirando Fluidify.

| Criterio | Voto | Stato verifica | Motivazione/evidenze | Problemi/gravitá |
|---|---|---|---|---|
| CP1.b | **NV** | Gate obbligatorio incompleto; connect negativo verificato | Baseline LP parzialmente attestata, nove richieste/ricezioni mode entro 5 s. Unico connect Fluidify fallito nella registrazione locale L2CAP. Mancano tre sessioni, controlli da entrambe le app e gesti, CA bidirezionale hardware, conversazioni e interferenze volume/pausa, lifecycle prodotto. | **Bloccante per avanzamento:** convivenza funzionale non dimostrata. Il negativo del connect resta esplicito; NV non lo cancella. |
| CP5 | **8/10** | Adeguate per localizzare il blocco e decidere di non avanzare | Integrità verificata; APK identificato; correlazione trasporto/errore/cleanup riscontrata nei dati originali; transazioni LP ricalcolate. Evidenze sufficienti alla diagnosi di registrazione, insufficienti alla certificazione del gate completo. | **Limiti sostanziali dichiarati:** singolo tentativo, nessun inventario runtime RCB, evento server concorrente senza misura dell’impatto funzionale, CA e scenari successivi non attestati. |

Ho eseguito personalmente controlli locali in sola lettura:

- Hash e dimensioni conformi per **16/16 artefatti G1**; hash conformi per **80/80 file dello snapshot I3**. Entrambi i manifest coincidono con gli SHA256 forniti. Il report root è stato soltanto sottoposto a hash.
- L’APK estratto dal telefono ha SHA256 `dd5e5120…46d2d2` e coincide esattamente con **`fluidify-I3-listener.apk`**. È distinto da `fluidify-I3.apk`. Metadati: `dev.pampa.fluidify`, versione **2.0.1 (24)**; verifica della firma locale riuscita.
- `versions.txt` registra Samsung Android 17 e LibrePods **1.0.4-play**, codice `100040301`. Sono dati della raccolta congelata, non una mia interrogazione attuale del telefono.
- Nessun AGENTS applicabile trovato. Lette le skill richieste; nessuna build, installazione, operazione dispositivo, delega o scrittura. Non consultati i report vietati.

La correlazione decisiva è nel [log congelato](</C:/VibeCoded Projects/Fluidify/build/airpods-worn-gate/continuous.log:114245>): alle **19:57:11.419** la richiesta UID10595/PID31173 usa **TYPE_L2CAP=3, PSM4097/0x1001**, coerentemente con il trasporto BR/EDR dello snapshot. Seguono `no RCB available`, vPSM `0x1004`, errore `GAP_ConnOpen`, inizializzazione fallita del socket **83** e liberazione immediata; lato app il socket viene chiuso alle **19:57:11.420**. Non risultano connessioni Fluidify riuscite. Lo screenshot ERROR corrobora il negativo, senza costituire da solo la diagnosi.

Questo localizza il blocco **nella registrazione locale, prima dello scambio AACP**. Sorgenti AOSP forniti e disassembly Samsung sono coerenti con un allocatore RCB finito e un’allocazione fallita; anche l’hash del binario Samsung coincide. Non permettono però di identificare tutti gli occupanti runtime, attribuire l’esaurimento esclusivamente a LP, diagnosticare una perdita di risorse o affermare un limite universale alla convivenza.

L’[evento UID1002](</C:/VibeCoded Projects/Fluidify/build/airpods-worn-gate/continuous.log:114257>) merita registrazione separata: nello stesso millisecondo viene liberato il socket server **77**, canale4097; seguono eccezione **OBEX accept**, creazione del socket **84** e registrazione server PSM `0x1001`. È una perturbazione tecnica osservata temporalmente associata al tentativo. **Non è il socket LP**, né dimostra da sola un danno funzionale o la sua riproducibilità. Sarebbe scorretto descrivere il tentativo come privo di qualsiasi evento collaterale; sarebbe altrettanto scorretto dichiarare LP interrotta.

Per LP ho ricalcolato le nove coppie richiesta/ricezione `0d`: **3 ANC, 3 Transparency, 3 Adaptive**, tutte entro5s, massimo **3,385s**. Sono ricezioni distinte dalle scritture ottimistiche, ma non certificano convergenza fra due app o risposta ai gesti. Dopo il tentativo risultano ricevute ANC alle **19:59:19.108** e Adaptive alle **19:59:21.866**, senza connect/close LP rilevati nella cattura. Il ripristino Adaptive è riscontrato; `initialMode=Adaptive` nell’estrattore è invece un campo dichiarato, non ricavato automaticamente.

Il detach diagnostico delle **19:58:57.677** è seguito da **183,097s** di cattura senza nuovo attach diagnostico o connect Fluidify. MediaSession passa da PLAYING a PAUSED al termine. Questo sostiene il cleanup osservato del POC; non certifica lifecycle o assenza di servizi permanenti di prodotto. Rilascio del pannello e arresto del logcat restano dichiarazioni della raccolta.

Indossamento, audio baseline regolare e assenza di interruzioni percepite sono **riscontri attribuiti all’utente**, non mie misure. Non attestano CA, tre conversazioni o assenza di doppia attenuazione.

La prossima decisione tecnicamente sostenuta è **mantenere il gate NV e fermare l’avanzamento prodotto**, conservando distinti il connect fallito e gli scenari non eseguiti. Nel perimetro attuale può proseguire soltanto l’analisi statica dei materiali congelati sulla registrazione e sul percorso di cleanup server. Questi dati non autorizzano patch speculative, retry o ulteriori prove hardware.

