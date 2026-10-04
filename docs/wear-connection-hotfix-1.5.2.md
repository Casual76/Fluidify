# 1.5.2: controllo del collegamento Wear

La 1.5.1 mostrava `Bluetooth` sia per una capability assente/non vicina sia per il fallimento del messaggio di offerta. La prima verifica poteva impedire di tentare un invio al Watch già identificato tramite hello. La schermata da sola non permette di distinguere questi due casi.

Il telefono verifica ora l’ID noto tramite `NodeClient.connectedNodes`, mantiene `isNearby` per escludere i nodi indiretti/cloud e attende fino a sei secondi una riconnessione. Le capability servono alla scoperta della app; non vengono interrogate nuovamente per autorizzare ogni offerta/invio. Sul Watch la scoperta incrocia gli ID annunciati con i nodi connessi correnti; dopo un hello usa direttamente il nodo noto. Anche gli eventi delle capability vengono verificati contro il collegamento corrente.

Aprire la sezione Watch del telefono invia un nuovo hello ai Watch della app direttamente collegati. Questo aiuta a recuperare il peer dopo la ricreazione del processo o mentre è ancora installata una versione Wear precedente. Il controllo manuale consulta prima il manifest: un vecchio APK conservato non precede una versione più recente. Se il manifest fallisce, resta disponibile il file conservato.

Gli errori di connessione e di invio del messaggio hanno testi distinti nelle otto lingue del telefono. Nessuna richiesta Wi-Fi è stata aggiunta.

Test: cinque regressioni su nodo noto senza interrogazione capability, ricerca temporaneamente fallita, riconnessione, nodo cloud/altro Watch, timeout della query e cancellazione; tre su scoperta del telefono dal Watch con capability vecchie o assenti. App dev: 14 test; Wear dev: 69 test, tutti passati. Build release minificate e lint API mirato verificati prima della pubblicazione.

Nessun dispositivo ADB disponibile: il difetto corretto è riprodotto con finti dei due elenchi; la causa concreta sul dispositivo dell’utente e il trasferimento Bluetooth reale restano da verificare. Checklist nella sezione 1.5.2 di `wear-qa-checklist.md`.

Riferimenti ufficiali: [NodeClient](https://developers.google.com/android/reference/com/google/android/gms/wearable/NodeClient) e [Node.isNearby](https://developers.google.com/android/reference/com/google/android/gms/wearable/Node).
