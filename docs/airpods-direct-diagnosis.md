# AirPods diretto — diagnosi della registrazione Samsung

Data: 2026-11-07. Approfondimento autorizzato dall’utente dopo I3; APK I3 invariato e nessuna modifica prodotto introdotta dalla ricognizione. Questa è ricognizione, non una review formale né un nuovo giro correttivo.

Nota sul checkout condiviso: nessuna modifica prodotto introdotta da questa ricognizione. Nel controllo finale il PlaybackService live contiene nuove integrazioni esterne della continuità della cronologia (restoredListeningOccurrence e historyHandoff), preservate. Tali differenze non appartengono all’approfondimento AirPods e non sono incluse nell’APK I3 congelato, il cui hash resta invariato. I risultati I3 si riferiscono al suo snapshot e artefatto, non certificano queste modifiche concorrenti successive.

## Conclusione operativa

Il client diretto Fluidify ha aperto il trasporto e ricevuto identità/batterie in una sessione. Il binario Bluetooth estratto dall’S25 contiene un pool classico di 15 RCB; ogni nuovo socket outgoing AACP nei percorsi verificati richiede una nuova registrazione. Il fallimento del tentativo concorrente LibrePods è coerente con pool esaurito. Non sono stati misurati i proprietari delle voci o la durata dell’occupazione; non si conclude che la convivenza sia sempre impossibile.

Nessuna modifica del client è motivata prima della prossima prova discriminante. Nessun cambio UUID, costruttore alternativo, accesso Binder o variante insecure verificati aggira la stessa registrazione. Il gate totale resta NV e non abilita UI/A2DP/lifecycle di prodotto.

## Riscontro dell’orchestratore

Root ha ripetuto `python -B build/airpods-direct-research/reproduce-elf.py` sul binario con hash atteso. Riproduzione riuscita; il disassemblato dell’allocatore contiene 15 letture dei flag in-use, quindi il ritorno null a 0xaad948 se tutte risultano occupate. I simboli estratti confermano indirizzi/dimensioni delle funzioni. È verifica della capienza compilata, non fotografia della memoria runtime.

- Binario: `build/airpods-direct-diagnostics/libbluetooth_jni-s25.so`.
- SHA256: `8040446777e75c99c2f975e7c090900d048aab78e55a5a8fca67dca1ad4c96a8`.
- `l2cu_allocate_rcb`: VA0xaad81c, size0x148.
- `l2cu_find_rcb_by_psm`: VA0xaadc44, size0x1f4.
- `L2CA_Register`: VA0xa88b68, size0x3a0.
- Evidenze e script temporanei ignorati da Git: `build/airpods-direct-research/`. Nessuna scrittura sul dispositivo o sul binario.

## Procedura discriminante — eseguita nelle evidenze G1

Prerequisito successivamente confermato dall’utente: entrambe le AirPods indossate; LibrePods operativa e audio locale continuo osservati. Nessun altro collegamento Bluetooth è stato disattivato per liberare risorse. La procedura seguente è stata eseguita una volta, fermandosi al connect fallito; evidenze in airpods-worn-gate.md e valutazione indipendente in airpods-gate-review-G1.md.

1. Registrare una finestra continua correlando UID10556 LibrePods e UID10595 Fluidify, socket e timestamp; confermare un aggiornamento recente LibrePods e assenza di chiusura del suo canale prima del tentativo.
2. Un solo connect manuale Fluidify, senza handshake o sottoscrizione. Se LibrePods perde il canale prima, la baseline è invalida e la prova viene interrotta.
3. Se il connect fallisce con noRCB e LibrePods rimane collegata, il blocco è già locale e precedente a qualsiasi pacchetto AACP; chiudere Fluidify e conservare l’evidenza. Nessuna diagnosi permanente basata su timeout generici.
4. Soltanto con entrambi i canali aperti procedere a handshake e poi sottoscrizione minima, verificando LibrePods dopo ogni passaggio. Usare la finestra background diagnostica limitata a90s quando occorre osservare LibrePods.
5. Chiudere Fluidify, verificare continuità o recupero LibrePods e osservare almeno due minuti senza nuove aperture Fluidify. Nessun retry ciclico, takeover o rilascio di risorse altrui.

Un singolo esito positivo non completa il gate: restano i tre cicli e le verifiche bidirezionali modalità/CA, audio, gesti e lifecycle previste dal piano approvato. Una baseline instabile resta NV, distinta da un conflitto riproducibile introdotto e rimosso dal client.

## Nota statica successiva sull’evento server

Nel riferimento AOSP fissato, btif_sock_l2cap.cc inizializza il nuovo sock->handle a0 (riga338). In on_cl_l2cap_init, il ramo di errore libera il socket prima di assegnare il handle ricevuto (righe518–524). Il cleanup del client chiama BTA_JvL2capClose anche per handle0 (righe291–292); bta_jv_api.cc257–265 accetta un handle entro range con callback presente, comprendendo quindi0.

**Inferenza da verificare, non diagnosi Samsung conclusiva:** questo percorso può spiegare la chiusura di una precedente registrazione associata all’handle0 quando il nuovo client fallisce prima di acquisirne uno. La sequenza è coerente con gli eventi UID1002/OBEX di G1, ma non è stato misurato l’handle effettivo del server77 né provata l’equivalenza completa di quel ramo nel binario Samsung, che espone anche differenze di firma rispetto al riferimento. Non si dichiara un danno funzionale o una sua riproducibilità. Non introduce una correzione eseguibile nel solo processo Fluidify e non giustifica tentativi aggiuntivi o patch allo stack.

## Ricognizione indipendente — report integrale

GPT-6.1 Sol, reasoning high. Nessun dispositivo, build, modifica prodotto o subdelega. Artefatti temporanei isolati; nessun nuovo giro formale di review.

<t3-thread://v2/0c1f503b-15df-4bc4-b7b1-2a6da10718fe/thread:delegated-task:command%3Amcp%3A9e63d7bf-66cc-44f7-b61a-b2691783ee87%3Adelegate-task%3Afbe611cc-c807-40bc-82d5-a279ddbb0880>

**Il binario Samsung permette di confermare un pool di 15 RCB classici. Non emerge un percorso nel solo client Android che eviti una nuova registrazione per un secondo socket AACP autonomo.** La prova già riuscita dimostra il trasporto diretto Fluidify; la convivenza con LibrePods resta NV.

Ricognizione conclusa senza dispositivi, build, implementazione o nuove deleghe. Ho creato soltanto artefatti in `build/airpods-direct-research`. Nessun nuovo giro di review o modifica dei contatori.

**1. Registrazione, pool e rilascio**

Nel riferimento AOSP fissato, il percorso è:

`BluetoothSocket.connect()` → Binder → JNI del servizio → `btsock_l2cap_connect` → `BTA_JvL2capConnect` → `GAP_ConnOpen` → `L2CA_RegisterWithSecurity` → `L2CA_Register`.

`GAP_ConnOpen` imposta a null la callback incoming per il client outgoing. Per PSM dinamici, `L2CA_Register` sceglie quindi un nuovo vPSM, partendo da `0x1002` e avanzando di due. Il PSM remoto rimane `0x1001`: **`vPSM 0x1004` non indica una porta remota errata né l’indice della quarta voce del pool**. [AOSP GAP](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/c9bfb6b54a99e35872ce40f9c1ed7d3e86a7dd1a/system/stack/gap/gap_conn.cc), [AOSP registrazione](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/c9bfb6b54a99e35872ce40f9c1ed7d3e86a7dd1a/system/stack/l2cap/l2c_api.cc).

Riferimenti locali: [gap_conn.cc:228](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-diagnostics/gap_conn.cc:228>), [l2c_api.cc:136](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-diagnostics/l2c_api.cc:136>).

Il pool AOSP è globale nello stack classico, condiviso dalle registrazioni dei vari utilizzatori: **15 registrazioni non significa 15 app o 15 connessioni**. Il default è configurabile dalla build. [Configurazione AOSP](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/c9bfb6b54a99e35872ce40f9c1ed7d3e86a7dd1a/system/internal_include/bt_target.h), [allocatore AOSP](https://android.googlesource.com/platform/packages/modules/Bluetooth/+/c9bfb6b54a99e35872ce40f9c1ed7d3e86a7dd1a/system/stack/l2cap/l2c_utils.cc).

Sul **binario Samsung fornito**, SHA256 corrispondente a quello indicato:

- `.gnu_debugdata` contiene simboli recuperabili mediante decompressione XZ.
- `l2cu_allocate_rcb` è a `0xaad81c`, dimensione `0x148`.
- Il codice esamina **15 voci**, con passo `0x90`, da VA `0xfc6b08` a `0xfc72e8`; quando risultano tutte occupate restituisce null.
- `l2cu_find_rcb_by_psm` controlla indipendentemente le stesse 15 voci.
- `L2CA_Register`, a `0xa88b68`, chiama quell’allocatore; il ramo null conduce al messaggio esatto `no RCB available`, con riferimento sorgente 156, coerente con il log Samsung.

Evidenze: [riepilogo ELF:1](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-research/samsung-rcb-evidence.txt:1>), [simboli:1](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-research/samsung-rcb-symbols.txt:1>), [allocatore disassemblato:7](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-research/samsung-allocate-rcb.txt:7>), [chiamata all’allocatore:77](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-research/samsung-register.txt:77>). Ho eseguito anche lo [script di riproduzione](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-research/reproduce-elf.py>).

Questo conferma la **capienza compilata del binario esaminato**. Non costituisce una misura runtime dell’occupazione o dei proprietari delle voci.

Per il rilascio, AOSP segue: chiusura del descrittore app → rilevamento EOF/eccezione nel servizio → `btsock_l2cap_free_l` → `BTA_JvL2capClose` → `GAP_ConnClose`. Quest’ultimo richiede la disconnessione e rilascia il CCB GAP; quando nessun altro CCB usa quel vPSM, lo deregistra. `L2CA_Deregister` libera infine l’RCB. [Chiusura GAP:322](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-diagnostics/gap_conn.cc:322>), [deregistrazione GAP:1033](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-diagnostics/gap_conn.cc:1033>), [rilascio RCB:1763](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-diagnostics/l2c_utils.cc:1763>).

Non emerge una prova di perdita permanente di RCB. Il ritorno di LibrePods dopo la chiusura Fluidify è coerente con risorse nuovamente disponibili, senza identificare quale voce sia stata liberata.

**2. Che cosa spiegano le evidenze Samsung**

La sequenza verificata nei log locali è coerente con contesa del pool:

| Evento | Evidenza |
|---|---|
| LibrePods aveva già perso il socket 74 alle 18:40:22.227 | `s25-direct-session.log:45794` |
| Fluidify UID10595 avvia socket75 e registra PSM1001 | stesso log, righe 62494–62498 |
| LibrePods UID10556 avvia socket76 e fallisce `no RCB`, vPSM1004 | stesso log, righe 62856–62866 |
| Fluidify socket75 raggiunge connessione con MTU8087 | stesso log, riga 63149 |
| Fluidify chiude; successivamente LibrePods socket79 si collega | `s25-direct-cleanup.log:95` e `:7633` |

**Inferenza forte:** il fallimento di registrazione è compatibile con tutte le 15 voci occupate in quell’istante. La combinazione log/binario sostiene questa spiegazione molto meglio del solo default AOSP.

**Non provato:** chi occupasse le altre voci, quanto fosse transitoria l’occupazione, oppure che su Samsung possano funzionare soltanto uno o due client. Non abbiamo una fotografia runtime del pool.

Il tentativo fallito delle 18:42:28.531 appartiene a LibrePods. Il collegamento e i dati diretti Fluidify restano validi; manca però una baseline LibrePods contemporaneamente stabile, quindi nessun PASS dual-channel.

**3. Alternative nel solo client**

Ho verificato le cinque firme contro [LibrePods pinned](https://github.com/librepods-org/librepods/blob/89356c08cea3db0b6b54792d9329d09a87cc85fa/android/app/src/main/java/me/kavishdevar/librepods/bluetooth/BluetoothConnectionManager.kt). Sono alternative di costruzione dell’oggetto, non cinque trasporti differenti.

| Variante esaminata | Esito |
|---|---|
| Hidden `BluetoothDevice.createL2capSocket(int)` | Crea un `BluetoothSocket(TYPE_L2CAP, true, true, …)`; stesso percorso. |
| Variante insecure | Cambia autenticazione/cifratura; conserva registrazione GAP/RCB. |
| Chiamata Binder diretta | Converge nello stesso JNI/native, con UID del chiamante. |
| UUID diverso/null, flag SDP | Nel dispatch BR/EDR L2CAP l’UUID non seleziona un RCB esistente; nessun bypass individuato. |
| Riutilizzo del proprio socket aperto | Evita aperture ridondanti Fluidify, ma non la registrazione aggiuntiva rispetto a LibrePods. |
| Socket incoming/server | Richiede comunque registrazione; manca inoltre una prova che AirPods inizi il canale AACP necessario. |

Fonti locali: [factory hidden:2829](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-research/BluetoothDevice.java:2829>), [connect Binder:532](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-research/BluetoothSocket.java:532>), [UID Binder:78](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-research/BluetoothSocketManagerBinder.java:78>), [JNI:1801](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-research/com_android_bluetooth_btservice_AdapterService.cpp:1801>), [dispatch native:217](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-research/btif_sock.cc:217>), [passaggio a GAP:1073](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-research/bta_jv_act.cc:1073>).

Inoltre `L2CA_ConnectReq` richiede un RCB già registrato: non è un ingresso senza registrazione. [Controllo:308](<C:/VibeCoded Projects/Fluidify/build/airpods-direct-diagnostics/l2c_api.cc:308>).

**Conclusione circoscritta:** nei percorsi verificati non c’è una soluzione esclusivamente client, autonoma e senza privilegi che condivida implicitamente la registrazione LibrePods. Non consiglio workaround su costruttori, UUID, flag o JNI. LE e RFCOMM non sostituiscono questo protocollo.

**4. Prossimo esperimento minimo raccomandato**

Usare il diagnostico esistente, senza patch preliminari:

1. **Cooperazione utente:** confermare AirPods indossate e LibrePods funzionante. Osservare un evento fresco in LibrePods e audio continuo; mantenere invariati altri collegamenti Bluetooth durante la breve prova.
2. **Root:** acquisire una finestra continua con correlazione UID/socket e verificare che il socket LibrePods resti collegato prima di iniziare. La sola presenza del processo o della notifica non basta.
3. Eseguire **un solo tentativo Fluidify di connessione**, senza handshake, sottoscrizioni o comandi. Se LibrePods si disconnette prima dell’apertura Fluidify, la prova perde la baseline: interrompere.
4. Se Fluidify si collega e LibrePods rimane collegata, procedere per passi con handshake e sottoscrizione minima, osservando la continuità LibrePods dopo ciascuno. Il diagnostico ha già una finestra background limitata a 90 secondi per osservarla.
5. Chiudere soltanto Fluidify e verificare continuità o recupero LibrePods.

Un nuovo `no RCB` con LibrePods stabile isolerebbe il blocco **prima di qualsiasi pacchetto AACP**. Due connessioni contemporanee consentirebbero invece di separare il problema di registrazione dall’eventuale interferenza introdotta da handshake/sottoscrizione.

**Nessuna modifica client è attualmente motivata per questa prova.** Il gate fisico e la UI prodotto restano bloccati; eventuali esiti positivi andranno valutati rispetto ai criteri già concordati.

