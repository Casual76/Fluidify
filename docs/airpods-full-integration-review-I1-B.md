**Esito Reviewer B: correttivo necessario.** Ho identificato un difetto nel recupero del cleanup UNPAIRED. L’approvazione complessiva resta inoltre incompleta per i criteri indicati NV.

Modello effettivo: **GPT-6.1 Sol, reasoning high**. Fase **IMPLEMENTAZIONE**, giro **CORRETTIVO1**, versione **I1 stabile**; nessun reset dei contatori.

Ho lavorato sul [congelato I1](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I1-stable>), senza consultare review, voti o comunicazioni di altri critici. Nessuna operazione device/ADB, build Android, modifica ai sorgenti o delega. Artefatti personali esclusivamente nello scratch autorizzato.

| Criterio | Voto 0–10 | Stato verifica | Motivazione ed evidenze | Problemi / gravità |
|---|---:|---|---|---|
| I1 Completezza funzionale | NV | Esame parziale | Letti piano, copertura e C1–C6; approfonditi audio ordinario, assistant, chiamate e Tile. Non completata la riconciliazione individuale delle 35 famiglie, 67 preferenze e 41 esclusioni con tutti gli ingressi reali. | Completezza globale non certificata. |
| I2 Home/player/quick/full | NV | Esame statico parziale | Riscontrati host condiviso, quick, pagina completa e ingresso dall’onboarding signed-out. Non verificata integralmente la matrice geometrica/font/temi né il materiale nel completo host produttivo. | Nessun voto dedotto dalle sole fixture. |
| I3 Lifecycle e ownership | **7** | Approfondita: sorgente, XML parent e probe JVM personale | Callback differita con coppia/consenso/registrazione/epoch; rivalutazione C3/C4; gate prima di FGS; distinzione NONE/UNKNOWN/BONDING; lease comune e permit debug separato. | **B1, media:** cleanup CDM fallito perde l’indirizzo necessario al recupero. |
| I4 Protocollo/stato/capability | NV | Campionamento | Esaminati factory socket, guardie, separazione RX/richieste e test codec su troncamenti, identità e batterie. Non completata la verifica indipendente di tutti i layout/range e della serializzazione. | Campionamento insufficiente per il voto globale. |
| I5 Automazioni e controllo manuale | NV | Esame parziale | Esaminati ricontrollo dei comandi accodati, consenso background/C6, test correttivi receipt e ramo chiamate senza ricaduta sul media nel percorso ringing riconosciuto. | Non completato l’audit dell’intera macchina C5 e degli adattatori renderer/volume. |
| I6 Validazione e artefatti | **8** | Controlli personali + evidenze parent ispezionate | XML estesi: 586 test/67 suite, zero failure/error/skip; letti corpi discriminanti. Ricontrollati personalmente firme, ZIP16KB, ELF64 e presenza JNI nei DEX. | Nessuna equivalenza completa sorgente/APK o esecuzione minificata dimostrata; nessun full lint PASS. |
| I7 Confini, permessi, provenienza | **8** | Statica approfondita | Manifest e rami CDM coerenti; sole associazioni proprie; richieste audio/assistant/call distinte dagli effetti. Verificate 133 copie pinned. Nessuna nuova espansione JNI per l’adattatore audio ordinario. | Nessun difetto confermato nel perimetro esaminato. Funzionamento OEM NV. |
| I8 Integrazione e preservazione | **7** | Statica approfondita sul focus | Wiring Application→controller→service→factory reale; default `device()` effettivo; NLS osservativo, Tile senza acquisizione, gate prodotto FALSE. Confronto dei manifest: contesto core/Wear/Engine preesistente invariato. | **B1, media:** integrazione incompleta del recupero dopo errore CDM. |
| H1 Gate fisico standalone | NV | Non eseguibile | Telefono indisponibile; nessuna nuova evidenza fisica autorizzata. | Nessuna penalità hardware. **Conformance prodotto resta FALSE.** |

**B1 — Il fallimento della disassociazione CDM perde il destinatario del cleanup**

**Trigger:** “Dimentica” o BOND_NONE sulla coppia selezionata; `disassociate()` restituisce false o solleva un’eccezione transitoria.

La sequenza è concreta:

1. [AirPodsAndroidRuntime.kt:281](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I1-stable/source/app/src/main/java/dev/lelonio/square/airpods/runtime/android/AirPodsAndroidRuntime.kt:281>) legge l’indirizzo e chiama prima `gate.stop(UNPAIRED)`.
2. [AirPodsStartGate.kt:180](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I1-stable/source/app/src/main/java/dev/lelonio/square/airpods/runtime/lifecycle/AirPodsStartGate.kt:180>) elimina subito record della coppia e `selectedPair`.
3. [CompanionObservationPolicy.kt:105](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I1-stable/source/app/src/main/java/dev/lelonio/square/airpods/runtime/lifecycle/CompanionObservationPolicy.kt:105>) può restituire `DISASSOCIATE_FAILED`.
4. Una successiva `forget()` legge `selectedPair=null` e salta `companion.unpair(address)`. Anche la UI disabilita FORGET senza selezione, in [AirPodsHost.kt:123](</C:/VibeCoded Projects/Fluidify/build/review-airpods-full-implementation-I1-stable/source/app/src/main/java/dev/lelonio/square/ui/airpods/host/AirPodsHost.kt:123>).

**Evidenza personale:** il [probe JVM](</C:/VibeCoded Projects/Fluidify/build/airpods-full-integration/reviewer-scratch/I1-B/unpair-probe.txt>) esegue gate/store/policy dai bytecode congelati e produce:

```text
gate.stop=Stopped(generation=1)
companion.unpair=Denied(reason=DISASSOCIATE_FAILED)
selectedPair=null, pair record absent, failed disassociation attempts=1
pending cleanup address in persisted data=null
```

Il probe conferma la transizione dei componenti di dominio. L’impossibilità del successivo retry attraverso il controller è una **deduzione dal sorgente**, non un’esecuzione Android.

**Conseguenza e gravità:** **media**. L’associazione propria può restare nel sistema senza un percorso di recupero dedicato attraverso Dimentica. La soppressione rimane attiva: non ho dimostrato una riapertura non autorizzata.

**Fix minimo:** conservare durevolmente un destinatario di cleanup pendente, separato dalla selezione operativa, fino alla conferma della rimozione. Un retry esplicito deve rileggere e verificare l’associazione propria esatta; nessun self-retry.

**Regressione proporzionata:** fallimento di disassociazione, con e senza ticket; successivo retry esplicito anche dopo ricreazione del controller/processo. Verificare rimozione della sola associazione originaria, preservazione di un’eventuale nuova coppia e zero FGS/socket. Il fake di `AirPodsDeferredIngressTest` attualmente restituisce sempre true da `disassociate()`: il caso manca.

**Riscontri sul focus B**

La readiness differita consuma il pending prima della rivalutazione. Callback duplicate o cancellate non ottengono un secondo tentativo tramite quel pending; coppia, consenso, associazione, permesso e storico USER_REQUESTED vengono ricontrollati. I test parent pertinenti risultano presenti negli XML, ma contano FGS senza attach: **non dimostrano l’esecuzione della factory/socket**.

I default reali usano `AndroidBluetoothFacts`, registry Android e `device()` permission-gated verso `getRemoteDevice`; i seam aggiunti non sostituiscono questi default. La lease DIAGNOSTIC/PRODUCTION è condivisa. Il permit debug resta distinto dalla conformance prodotto. Audio, assistant e chiamate restituiscono richieste accettate senza dichiarare effetti osservati; Tile listening e NLS non acquisiscono l’owner.

**Attribuzione delle verifiche**

Ho eseguito personalmente hash/dimensioni, lettura XML, probe JVM, sei controlli firma/ZIP sui tre APK e parsing ELF/DEX: **14 ELF64 debug e 7 minified**, tutti con LOAD compatibili con 16 KB; classe JNI presente in entrambi. I [risultati artefatti](</C:/VibeCoded Projects/Fluidify/build/airpods-full-integration/reviewer-scratch/I1-B/independent-artifact-checks.json>) restano controlli strutturali.

I **27 mirati, 586 estesi, build Android e 15 checker** sono esecuzioni archiviate del parent, non mie. I **18 core/150 Wear** rimangono storici. Ho conservato nella valutazione i limiti dichiarati e le precedenti esecuzioni fallite presenti in `I1-integration.md`.

**Integrità finale riconfermata:** 1166/1166 file conformi, zero mismatch; screenshot esterno conforme per dimensione/hash. Manifest SHA-256 invariato:

`51627c5cb141cabccf85d95ca24f2430cf11bccbcd72c22d21e9bd0b5d198dac`

Software approvabile solo dopo risoluzione dei rilievi e completamento delle verifiche mancanti; **H1 resta NV e nessun esito software abilita il prodotto**.
