# POC AirPods sull'S25 — 7 novembre 2026

## Ambiente osservato

- Samsung SM-S931B, Android 17; fingerprint `samsung/pa1qxeea/pa1q:17/CP2A.260605.016/S931BXXUDDZJB_OXMDDZJB:user/release-keys`.
- LibrePods ufficiale `1.0.4-play`, versionCode `100040301`.
- Modello riportato da LibrePods: `A3064`. Nome utente non usato come prova del modello.
- Firmware riportato dall'interfaccia LibrePods: Version 1 e 2 `81.2675000075000000.6877`, Version 3 `8454800`. Sono le stringhe effettivamente mostrate, non una conversione in una versione Apple canonica.
- Auricolari collegati, **non indossati**, come dichiarato dall'utente.
- Stato iniziale visibile in LibrePods: Adaptive, conversation awareness ON; gesti prolungati L/R impostati su Listening Mode; Off Listening Mode disabilitato.
- APK Fluidify della prova: SHA256 `60d00724cc0dbe29854dfa2c05a2ca9e431c63852b5161b5c2286af3e16f0d9f`, conservato in `build/fluidify-airpods-ux-verified.apk`.

## Prova eseguita

Ingresso interno debug, concessione del solo permesso Bluetooth richiesto, scelta esplicita della coppia già abbinata. Nessuna modifica a LibrePods, abbinamenti, impostazioni degli auricolari o stack di sistema.

1. Il caricamento JNI e la costruzione del socket BR/EDR tipo 3 riescono: stato `CONSTRUCTED` osservato.
2. Il tentativo manuale delle **16:49:34 Europe/Rome** fallisce durante `connect()`, prima di handshake, feature flags o sottoscrizioni. Lo stack registra:

   ```text
   L2CA_Register: L2CAP - no RCB available, PSM: 0x1001  vPSM: 0x1004
   GAP_ConnOpen: Failure registering PSM 0x1001
   on_cl_l2cap_init: Initialization status failed
   ```

   La chiamata risulta del processo Fluidify, tipo 3, porta 4097 e UUID AACP previsto. Il socket viene chiuso dopo l'errore.
3. LibrePods continua a mostrare la coppia collegata, Adaptive e batterie aggiornate (auricolari 79%, custodia 56% nella successiva osservazione). Non è una prova di continuità audio o conferma hardware delle modalità.
4. Un secondo tentativo **manuale**, alle **16:51:41**, dopo ritorno all'Activity tramite schermata applicazioni recenti, produce lo stesso errore di registrazione. Nessun retry automatico.
5. Uscita esplicita dalla diagnostica alle **16:52:19**. Fino alle **16:54:37**, nessuna nuova apertura attribuita all'UID Fluidify nei log Bluetooth esaminati. Questo verifica soltanto l'arresto del POC nelle condizioni osservate.

Log locali ignorati da Git: `build/s25-airpods-first-connect.log`, `build/s25-airpods-second-connect.log`. Screenshot successivo: `build/s25-librepods-after-poc.png`. I log completi possono contenere informazioni di altre applicazioni e non sono inclusi nel repository.

## Esito e limiti

**Gate complessivo: NV, non PASS.** L'errore locale di connessione è riprodotto due volte. Non è stata completata la baseline indossata prevista dal piano, né raggiunto un canale AACP aperto da Fluidify. Non sono stati inviati comandi, handshake o sottoscrizioni: batterie e identità sopra provengono dall'interfaccia LibrePods, non dal repository Fluidify.

Il punto di arresto è la registrazione locale del canale, non l'accesso JNI. Nel [sorgente AOSP di riferimento](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/c9bfb6b54a99e35872ce40f9c1ed7d3e86a7dd1a/system/stack/l2cap/l2c_api.cc) quel messaggio è emesso quando non si riesce ad allocare un registration control block. **Inferenza:** risorse o limiti dello stack meritano approfondimento; questo sorgente non prova la causa nella build Samsung e l'errore non dimostra da solo che due client siano sempre impossibili. Nessuna diagnosi generale per Android 17 o Android 16.

Il costruttore segue le cinque firme del [riferimento LibrePods fissato](https://github.com/librepods-org/librepods/blob/89356c08cea3db0b6b54792d9329d09a87cc85fa/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/BluetoothConnectionManager.kt). La versione installata resta distinta da quel commit.

Restano NV: connessione/handshake/notifiche Fluidify, conferma hardware dei comandi e del toggle CA nelle due direzioni, tre sessioni di convivenza, audio/gesti/CA indossata, lifecycle di prodotto, route A2DP e riapertura dopo veri eventi Bluetooth. La UI AirPods di prodotto resta subordinata al PASS. Prima di un eventuale nuovo esperimento serve distinguere il limite locale di registrazione dalla convivenza; nessun bridge, fork, root o modifica LibrePods viene introdotto automaticamente.

## Approfondimento del client diretto — dalle 18:38

L’utente ha scelto di approfondire il client diretto e autonomo. Prove eseguite con lo stesso APK I3 già approvato, SHA256 `dd5e5120b78959587399396dd794f7b2c9f405afe44dbf2cde86aa4c9046d2d2`: nessuna modifica al codice o alle impostazioni LibrePods/sistema. La disponibilità degli auricolari indossati è stata richiesta nuovamente; al momento di queste prove non è confermata.

### Riferimento dello stack

Nel medesimo commit AOSP di riferimento, [l2cu_allocate_rcb](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/c9bfb6b54a99e35872ce40f9c1ed7d3e86a7dd1a/system/stack/l2cap/l2c_utils.cc) cerca una voce libera nel pool globale `rcb_pool[MAX_L2CAP_CLIENTS]` e restituisce null quando non la trova. [bt_target.h](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/c9bfb6b54a99e35872ce40f9c1ed7d3e86a7dd1a/system/internal_include/bt_target.h) imposta il default a 15, modificabile dalla build. Questo non misura il limite effettivo Samsung né l’occupazione delle singole voci. Il percorso outgoing usa un PSM virtuale separato per ogni registrazione; il valore 0x1004 nei log non è la porta remota sbagliata.

### Connessione riuscita e prima lettura Fluidify

- Il log mostra LibrePods UID10556 collegata alle 18:38:54.539, poi chiusa alle 18:39:17.925; nuova connessione alle 18:39:33.342, chiusa alle 18:40:22.227. Queste chiusure precedono il tentativo Fluidify; l’interfaccia LibrePods da sola non è prova di un socket rimasto aperto fino alla prova successiva.
- Alle **18:42:28.345** il tentativo manuale Fluidify UID10595 registra con successo il PSM1001, socket id75. Alle **18:42:28.654** lo stack conferma `notify_app_connected`, MTU8087; la diagnostica abilita Handshake.
- Alle **18:42:28.531** un tentativo concorrente LibrePods UID10556, socket id76, fallisce con `no RCB available`, vPSM1004. In questa prova il messaggio appartiene a LibrePods, non a Fluidify: la correlazione UID/socket è necessaria.
- Inviati manualmente soltanto handshake e sottoscrizione minima, senza feature flags opzionali, cambi di modalità o toggle CA. La diagnostica riceve identità A3064, produttore Apple Inc., firmware `81.2675000075000000.6877`, stato modo OFF, CA true e batterie L78/R80/custodia100. Screenshot locale `build/airpods-direct-diagnostics/s25-direct-subscribed.png`, ricevute riferite alla generazione2.
- I valori di batteria sopra sono **ricezioni dirette Fluidify**, diversamente dalle sole osservazioni LibrePods della prima prova. Non confermano comportamento acustico, sincronizzazione tra app o efficacia dei comandi.
- Uscita esplicita alle **18:44:45.975**, chiusura confermata del socket75 Fluidify. Nessuna finestra background armata. Riaperta normalmente LibrePods e premuto una volta Reconnect: connessione UID10556 confermata alle **18:45:37.074**, con batterie aggiornate nell’interfaccia. Log osservati fino alle **18:47:44**, oltre due minuti dopo l’uscita: nessuna nuova apertura attribuita all’UID Fluidify.

Log completi locali, ignorati da Git: `build/airpods-direct-diagnostics/s25-direct-session.log` e `s25-direct-cleanup.log`; copie dei sorgenti AOSP di riferimento nella stessa directory. Libreria Bluetooth Samsung letta senza modifiche da `/apex/com.android.bt/lib64/libbluetooth_jni.so`, SHA256 `8040446777e75c99c2f975e7c090900d048aab78e55a5a8fca67dca1ad4c96a8`, per eventuale verifica dei limiti effettivi; nessun codice della libreria eseguito fuori dal sistema.

### Esito aggiornato

**Connessione, handshake e letture autonome dimostrati in una sessione; gate complessivo ancora NV.** Il ramo non è più fermo soltanto prima dell’handshake. La convivenza resta da dimostrare: in questa prova Fluidify è entrata dopo la chiusura del precedente socket LibrePods e il tentativo successivo di LibrePods non ha trovato una registrazione libera. Non è una baseline contemporanea stabile né una prova universale di incompatibilità. Restano obbligatori i tre cicli, conferme bidirezionali, CA/audio/gesti indossata e lifecycle previsti dal piano. Nessuna UI AirPods di prodotto viene sbloccata.

## Analisi successiva del binario Samsung

La ricognizione indipendente ha recuperato i simboli compressi in .gnu_debugdata e individuato l’allocatore nel binario letto dall’S25. Il disassemblato conferma 15 voci classiche RCB, non soltanto il default AOSP. Root ha riprodotto lo script e verificato le 15 letture dei flag in-use. Questo misura la capienza compilata della libreria con hash sopra; non misura occupazione runtime o proprietari delle voci.

Nei percorsi client esaminati ogni nuovo socket BR/EDR AACP richiede una nuova registrazione. Non è emerso un bypass basato su UUID, costruttore, cifratura o Binder; nessuna modifica ipotetica viene introdotta. La prova discriminante successiva usa il diagnostico esistente con LibrePods stabilmente collegata, un solo connect e inizializzazione progressiva soltanto se i due canali coesistono. Procedura, riscontri root e report integrale in [airpods-direct-diagnosis.md](airpods-direct-diagnosis.md). La conferma degli auricolari indossati è ancora necessaria prima della baseline completa.
