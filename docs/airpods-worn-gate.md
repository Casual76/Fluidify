# AirPods — prova concorrente con auricolari indossati

Data: 2026-11-07. Software eseguito: APK I3, nessuna modifica al client per questa prova. S25 SM-S931B, Android17 build CP2A.260605.016 / S931BXXUDDZJB; LibrePods ufficiale1.0.4-play, versionCode100040301. Hash dell’APK installato verificato tramite lettura: `dd5e5120b78959587399396dd794f7b2c9f405afe44dbf2cde86aa4c9046d2d2`.

## Preparazione e baseline osservata

- L’utente conferma: «Sono indossate e collegate all’S25».
- Il dumpsys iniziale registra LibrePods sul canale classico4097 collegato dalle19:48:44.581. La cattura continua copre19:50:07.862–20:02:00.774. In questa finestra non risultano nuove richieste connect o chiusure socket attribuite a UID10556 LibrePods.
- Riproduzione avviata dalla UI Fluidify alle19:53:25; MediaSession PLAYING e AudioTrack UID10595 started, uscita A2DP AirPods attiva. Il selettore mostrava già l’S25 come dispositivo attivo; nessuna azione di trasferimento Connect è stata selezionata.
- In LibrePods eseguiti tre cambi per ciascuna modalità selezionabile ANC, Trasparenza e Adaptive. Per le nove richieste tra19:54:10 e19:54:51 esiste una ricezione pertinente `Control command received: 0d` entro5s; ritardo massimo osservato3.385s. Ripristinata Adaptive con ricezione04 alle19:54:53.753. Off non selezionabile nella configurazione osservata, non forzato.
- L’utente conferma la baseline percepita: «Sì, audio e cambi regolari».
- Si tratta della baseline necessaria a isolare la registrazione concorrente, non della baseline completa di tutti i gesti/CA prevista per il gate finale. Questi ultimi scenari non sono attestati da questa prova.

## Tentativo singolo Fluidify

Alle **19:57:11.419**, con LibrePods rimasta collegata e audio locale attivo, viene eseguito un solo connect manuale Fluidify:

| Passaggio | Riscontro |
|---|---|
| Chiamante | UID10595 / PID31173, socket83 |
| Trasporto | Tipo3 BR/EDR, PSM4097, UUID AACP previsto |
| Registrazione locale | `L2CA_Register: no RCB available`, PSM1001, vPSM1004 |
| GAP / inizializzazione | Failure registering PSM1001; initialization failed socket83 |
| Cleanup del tentativo | Socket83 UID10595 liberato nello stesso millisecondo |
| UI diagnostica | Generazione3, ERROR, IOException; Handshake disabilitato |

Nessun handshake, feature flag, sottoscrizione, comando modalità o toggle CA è stato inviato dal client Fluidify. Nessun retry automatico o tentativo aggiuntivo dopo il fallimento.

Nel medesimo contesto lo stack chiude anche un socket server UID1002/id77 e alloca id84. Non sono socket LibrePods e non sono stati chiusi manualmente da root. L’effetto funzionale di questi eventi di sistema non è stato verificato; non si dichiara assenza di qualunque effetto su altri profili.

## Verifica successiva e rilascio

- L’Activity diagnostica viene chiusa tramite Back di sistema; `dispatchDetachedFromWindow` alle **19:58:57.677**.
- LibrePods continua a ricevere comandi hardware: ANC alle19:59:19.108 e Adaptive alle19:59:21.866 dopo due azioni sulla sua UI. Stessi PID26763 e thread di ricezione30084; nessuna riconnessione o chiusura del suo socket nella finestra catturata.
- MediaSession Fluidify ancora PLAYING dopo il tentativo. L’utente conferma: «Sì, nessuna interruzione percepita».
- Log osservati fino20:02:00.774, oltre due minuti dopo l’uscita dalla diagnostica: una sola richiesta connect Fluidify nell’intera cattura, nessuna successiva riapertura.
- Musica avviata per il test messa in pausa dalla UI; MediaSession finale PAUSED. Adaptive ripristinata. Processo logcat creato da root terminato dopo verifica di PID/command line; pannello dispositivo rilasciato. Nessun servizio Bluetooth o LibrePods fermato, nessun fix di sistema o cambio policy.

## Interpretazione e limiti

**La connessione concorrente è fallita in questa prova**, prima del protocollo AACP. La continuità LibrePods e audio è stata osservata con log, controlli successivi e riscontro dell’utente. La prova autonoma precedente rimane valida: Fluidify può collegarsi e leggere dati quando riesce a ottenere la registrazione.

Questi risultati, insieme al pool classico15RCB verificato sul binario Samsung, sostengono il limite di risorse nella configurazione testata. Non misurano proprietari/occupazione di ogni voce né dimostrano impossibilità su ogni configurazione o dispositivo Android17.

**Nessun PASS del gate complessivo.** Restano non eseguiti: tre sessioni di convivenza, comandi Fluidify e conferme bidirezionali modalità/CA, tre conversazioni, gesti dedicati e lifecycle di prodotto. Il test non dimostra un degrado LibrePods/audio introdotto da Fluidify; non si attribuisce automaticamente il FAIL complessivo definito dal piano soltanto alla presenza di un errore. La classificazione finale delle evidenze viene sottoposta a due reviewer indipendenti.

La UI AirPods di prodotto rimane subordinata al gate già concordato. Nessuna modifica prodotto o cambio automatico verso bridge/fork.

## Evidenze riproducibili

Directory locale ignorata da Git: `build/airpods-worn-gate/`.

- `continuous.log`, SHA256 `1e917c77d9997bce07a8c979c70778f4857fcca19114f07d2e0ef96fec320c7a`. Il log completo contiene anche dati di altre app: non pubblicare o copiare fuori dal perimetro locale.
- `evidence.json`: estratti pertinenti, conteggi, ricevute e riscontri utente attribuiti.
- `summarize-evidence.py`: ricostruisce gli estratti e associa le nove richieste baseline alle ricezioni successive entro5s.
- `versions.txt`, `baseline-bluetooth.txt`, `before-fluidify-bluetooth.txt`, `after-connect-bluetooth.txt`, `baseline-audio.txt`, `baseline-media.txt`, `after-connect-media.txt`, `final-media.txt`.
- `librepods-baseline.png`, `fluidify-connect-error.png`, `librepods-after.png`.
- `installed-fluidify.apk`: lettura dell’APK effettivamente installato, hash corrispondente a I3.

Questa verifica di evidenze hardware non modifica la versione software I3 o i due giri correttivi software già consumati; non è un espediente per riavviare il contatore. Nessuna nuova build o review del codice invariato viene dichiarata.
