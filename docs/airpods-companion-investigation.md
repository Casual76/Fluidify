# AirPods — integrazione con LibrePods ufficiale

## Stato successivo: percorso archiviato

L’utente ha poi rifiutato esplicitamente widget e workaround. Il probe temporaneo è stato
rimosso dall’S25 insieme alle sole associazioni create dal probe. La ricerca corrente
è descritta in `airpods-coexistence-8h.md`; questa nota conserva soltanto la ricognizione storica.

## Preferenza al momento della ricognizione

L’utente vuole conservare LibrePods ufficiale, le sue altre funzioni e gli aggiornamenti frequenti. L’uso di un’app alla volta è soltanto un ripiego condizionato all’assenza di alternative convincenti; non viene trattato come scelta definitiva o via libera all’implementazione esclusiva.

P1 e gate G1 restano invariati: il client diretto ha letto dati da solo, ma il connect concorrente sull’S25 è fallito. Nessuna UI AirPods di prodotto viene sbloccata automaticamente.

## Nuova pista tecnica: widget ospitati

La versione ufficiale installata1.0.4-play contiene due provider Android:

- me.kavishdevar.librepods.presentation.widgets.NoiseControlWidget
- me.kavishdevar.librepods.presentation.widgets.BatteryWidget

Manifest letto dall’APK realmente installato, hash baseAPK `5b7db3d157a89ae74b2f0c6b5482d85b2f2bd4bb4cb7687077de47351accb60b`. Entrambi i provider dichiarano AppWidget, category home/lock, minimo180×40dp. Il receiver exported=false è distinto dal protocollo di hosting tramite il servizio Android.

Nel [sorgente LibrePods fissato](https://github.com/librepods-org/librepods/blob/89356c08cea3db0b6b54792d9329d09a87cc85fa/android/app/src/main/java/me/kavishdevar/librepods/presentation/widgets/NoiseControlWidget.kt), i controlli passano per PendingIntent creati da LibrePods e il suo servizio corrente. Il [provider batteria](https://github.com/librepods-org/librepods/blob/89356c08cea3db0b6b54792d9329d09a87cc85fa/android/app/src/main/java/me/kavishdevar/librepods/presentation/widgets/BatteryWidget.kt) chiede al servizio di aggiornare la vista. La presenza nei sorgenti non equivale al collaudo dell’hosting sulla versione installata.

[Android documenta AppWidgetHost](https://developer.android.com/develop/ui/views/appwidgets/host) per incorporare widget di altri provider in un host. Il binding passa dal consenso di sistema; l’host gestisce ID persistenti, dimensioni, lifecycle, configurazione e rilascio. Questa è un’interfaccia supportata di presentazione/interazione, non un’API strutturata AirPods di lettura dello stato.

**Ipotesi da collaudare:** un pannello Fluidify può ospitare i widget ufficiali lasciando a LibrePods il singolo collegamento AACP. Il POC host non richiede Bluetooth, rete, socket o permessi privilegiati concessi via shell. LibrePods rimane originale e aggiornabile. Questa modalità sarebbe dipendente da LibrePods per i controlli; l’autonomia del client diretto rimane un’opzione separata e soggetta ai propri limiti.

## Copertura e punti ancora aperti

- Widget noti: batterie separate e modalità audio. Conversation awareness non è inclusa in questi due provider; verificare eventuali interfacce ufficiali esistenti senza promettere copertura completa.
- I dati visivi e lo stile interno restano di LibrePods. Non estrarre stato da nomi risorsa, colori, testo tradotto o geometria dei pulsanti per fingere un contratto stabile.
- La compatibilità con aggiornamenti richiede gestione della rimozione/cambio del provider, errore di inflazione e rinnovo della configurazione. Nessuna garanzia automatica per future versioni ancora non provate.
- Verificare aggiornamenti, tocchi, disponibilità del servizio, stato disconnesso e ripristino dopo ricreazione/riapertura del processo host. Nel riferimento il servizio invia nuove RemoteViews di stile/stato; occorre verificare che il normale hosting mantenga le azioni senza manipolare PendingIntent o componenti privati.
- Distinguere aggiornamento del widget da timestamp/conferma hardware. Questa pista non soddisfa automaticamente i criteri originari sui pending diretti.

## Prova tecnica proposta

Preparare un piccolo host diagnostico separato, temporaneo e senza modifiche all’APK FluidifyI3 o a LibrePods. Solo API AppWidget pubbliche; provider scelti tramite AppWidgetManager; conferma Android per il binding; ID appartenenti al solo host; start/stop listening e pulizia completa delle proprie associazioni.

Provare entrambi i widget, azioni della modalità, aggiornamenti e ripristino del processo. Registrare hash/versioni e verificare che il probe non apra canali Bluetooth. Solo dopo un risultato concreto aggiornare la proposta di prodotto/UX e i gate, con review indipendenti previste. Nessuna installazione fix, force-stop LibrePods, lettura delle sue preferences o fork.

Artefatti di discovery locali ignorati: build/airpods-official-integration. La ricognizione non modifica il codice prodotto né il verdetto softwareI3/hardwareG1.
